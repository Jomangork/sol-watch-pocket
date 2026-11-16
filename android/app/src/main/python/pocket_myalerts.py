"""My alerts: the user's own thresholds, checked against replies the bridge already reads.

A rule is {"id", "kind", "on", "loud", "value", and for some kinds "place", "good", "region" or "spaceport"}.
`check()` returns the alerts to post (a rule fires once when its line is crossed, and again only after the
value has gone back past it) and each rule's current value for the app's My alerts page. `choices()` lists the
places, goods, regions and spaceports the editor offers.

Times are real time: the game's clock runs `timeScale` (24) game days to a real day, so 1 game day is 1 real hour.
Money is in millions, as the game counts it (/me money 41671 is $41.7B), and so are the money rules' values.
"""
import pocket_calendar
import pocket_glance

KIND = "my_alert"  # the alert kind on the Alerts tab: Company · My alerts
KINDS = ("money_below", "money_above", "income_below", "stock_hours", "supplies_hours", "research_minutes",
         "market_stock", "spaceport_wait")
PATHS = ("/market",)  # read by the bridge (Market corruption check), tapped for market alerts
SPACEPORTS_PATH = "/spaceports"  # not read by the bridge: the app reads it only while a spaceport alert is on
SPACEPORTS_EVERY_SECONDS = 600


def _num(value):
    return value if isinstance(value, (int, float)) and not isinstance(value, bool) else None


def money_text(millions):
    """$612M, $41.7B, -$8.0M, $0.5M from an amount in millions (the game's unit)."""
    sign, amount = ("-" if millions < 0 else ""), abs(millions)
    if amount >= 1000:
        return f"{sign}${amount / 1000:.1f}B"
    if amount >= 100:
        return f"{sign}${amount:.0f}M"
    return f"{sign}${amount:.1f}M"


def hours_text(hours):
    if hours < 1:
        return f"{max(0, round(hours * 60))} min"
    if hours < 48:
        return f"{hours:.1f} h"
    return f"{hours / 24:.1f} days"


class Names:
    """Place and good names: the bridge's own (from its ctx) when it has them, else tidied ids."""

    def __init__(self, tapped, module=None, ctx=None):
        self.tapped, self.module, self.ctx = tapped, module, ctx

    def place(self, place_id):
        if self.module is not None and self.ctx is not None and hasattr(self.module, "place_name"):
            try:
                return self.module.place_name(self.ctx, place_id)
            except Exception:
                pass
        return pocket_calendar._name(place_id, self.tapped)

    def where(self, place_id):
        """The broad part of a place for the editor: its body ("Moon"), or its continent on Earth ("South America")."""
        rows = (self.tapped.get("/reference/places") or {}).get("places") or ()
        row = next((r for r in rows if isinstance(r, dict) and r.get("id") == place_id), None) or {}
        continent = row.get("continent")
        if isinstance(continent, str) and continent and (row.get("bodyId") in (None, "earth")):
            return continent.replace("_", " ").title()
        body = row.get("bodyId") or (str(place_id).split("/", 1)[0] if place_id else "")
        return pocket_glance._name(body, None, self.tapped.get("/reference/bodies")) if body else "Elsewhere"

    def good(self, good_id):
        names = (self.ctx or {}).get("good_names") if isinstance(self.ctx, dict) else None
        if isinstance(names, dict) and names.get(good_id):
            return names[good_id]
        return str(good_id).replace("_", " ").replace("/", " / ").capitalize() if good_id else "?"


def _game_now(tapped, me, now_ms):
    clock = pocket_calendar._Clock(tapped.get("/clock"))
    if clock.ok:
        return clock.day(now_ms), clock.scale
    return _num((me or {}).get("gameDay")), 24.0


def _stocks(tapped):
    return [s for s in ((tapped.get("/industry") or {}).get("industry") or {}).get("stock") or () if isinstance(s, dict)]


def _supplies(tapped):
    return [p for p in (tapped.get("/company") or {}).get("sustainment") or ()
            if isinstance(p, dict) and p.get("crew") and isinstance(p.get("supplies"), dict)]


def _markets(tapped):
    return [m for m in (tapped.get("/market") or {}).get("markets") or () if isinstance(m, dict)]


def _ports(tapped):
    body = tapped.get(SPACEPORTS_PATH)
    rows = body if isinstance(body, list) else (body or {}).get("spaceports") if isinstance(body, dict) else None
    return [p for p in rows or () if isinstance(p, dict)]


def reading(rule, tapped, me, now_ms, names):
    """(value, met, "Now …" text, title) for a rule, or None when the data isn't there yet."""
    kind, line = rule.get("kind"), _num(rule.get("value"))
    if line is None:
        return None
    game_day, scale = _game_now(tapped, me, now_ms)
    hours_per_game_day = 24.0 / scale
    if kind in ("money_below", "money_above", "income_below"):
        summary = pocket_glance.summarize(me, clock={"gameDay": game_day, "clock": {"timeScale": scale}}) if me else None
        money, rate = (summary or {}).get("money"), (summary or {}).get("incomeRate")
        if kind == "income_below":
            if _num(rate) is None:
                return None
            return (rate, rate < line, f"Now {money_text(rate)} a day",
                    "Income turns negative" if line == 0 else f"Income below {money_text(line)} a day")
        if _num(money) is None:
            return None
        trend = f", {money_text(rate)} a day" if _num(rate) is not None else ""
        if kind == "money_below":
            return money, money < line, f"Now {money_text(money)}{trend}", f"Money below {money_text(line)}"
        return money, money > line, f"Now {money_text(money)}{trend}", f"Money above {money_text(line)}"
    if kind == "stock_hours":
        place, good = rule.get("place"), rule.get("good")
        title = f"{names.good(good)} at {names.place(place)} within {hours_text(line)}"
        stock = next((s for s in _stocks(tapped) if s.get("regionId") == place and s.get("goodId") == good), None)
        if stock is None:
            return None if not tapped.get("/industry") else (None, False, "Not in our stock there now", title)
        qty, rate = _num(stock.get("qty")) or 0, _num(stock.get("rate")) or 0
        if rate >= 0:
            return None, False, f"Now {qty:,.1f} held, not falling", title
        hours = qty / -rate * hours_per_game_day
        return hours, hours < line, f"Now {qty:,.1f} held, runs out in {hours_text(hours)}", title
    if kind == "supplies_hours":
        place = rule.get("place")
        title = f"{names.place(place)} supplies within {hours_text(line)}"
        row = next((p for p in _supplies(tapped) if p.get("placeId") == place), None)
        days = _num((row or {}).get("supplies", {}).get("netDaysLeft")) if row else None
        if days is None:
            return None if not tapped.get("/company") else (None, False, "No crew supplies counted there now", title)
        hours = days * hours_per_game_day
        return hours, hours < line, f"Now {hours_text(hours)} left", title
    if kind == "research_minutes":
        title = f"Research finishes within {hours_text(line / 60)}"
        job = ((tapped.get("/research") or {}).get("research") or {})
        done = _num(job.get("completeDay"))
        if done is None or game_day is None:
            return (None, False, "No research running", title) if tapped.get("/research") else None
        name = pocket_glance._research(tapped.get("/research"), tapped.get("/reference/techs"), game_day) or {}
        minutes = (done - game_day) * hours_per_game_day * 60
        return minutes, minutes < line, f"{name.get('name') or 'Research'}: {hours_text(max(0, minutes) / 60)} left", title
    if kind == "market_stock":
        region, good = rule.get("region"), rule.get("good")
        title = f"{names.good(good)} in {names.place(region)}: stock under {line:g}% of target"
        row = next((m for m in _markets(tapped) if m.get("regionId") == region and m.get("goodId") == good), None)
        stock, target = (_num(row.get("stock")), _num(row.get("target"))) if row else (None, None)
        if stock is None or not target:
            return None if not tapped.get("/market") else (None, False, "Not on that market now", title)
        share = stock / target * 100
        return share, share < line, f"Now {share:.0f}% of target ({stock:,.0f} of {target:,.0f})", title
    if kind == "spaceport_wait":
        port = rule.get("spaceport")
        row = next((p for p in _ports(tapped) if p.get("regionId") == port), None)
        name = (row or {}).get("spaceport") or names.place(port)
        title = f"{name} spaceport wait under {line:g} days"
        wait = _num((row or {}).get("waitDays"))
        if wait is None:
            return None if not tapped.get(SPACEPORTS_PATH) else (None, False, "Not listed now", title)
        return wait, wait < line, f"Now {wait:g} days, {row.get('waiting') or 0} in line", title
    return None


ICONS = {"money_below": "💰", "money_above": "💰", "income_below": "📉", "stock_hours": "📦", "supplies_hours": "🥫",
         "research_minutes": "🧪", "market_stock": "🛒", "spaceport_wait": "🚀"}


def check(rules, tapped, me, now_ms, fired, names):
    """(alerts, status). alerts: [(rule, title, body)] to post now; fired: set of rule ids already posted, updated in
    place (a rule leaves it once its value is back on the safe side). status: {rule id: {"now", "met", "title"}}."""
    alerts, status = [], {}
    for rule in rules:
        rule_id = str(rule.get("id"))
        try:
            got = reading(rule, tapped, me, now_ms, names)
        except Exception as err:  # one odd reply mustn't stop the others
            status[rule_id] = {"now": f"Couldn't read: {err.__class__.__name__}", "met": False, "title": ""}
            continue
        if got is None:
            status[rule_id] = {"now": "Waiting for the check's next read", "met": False, "title": ""}
            continue
        value, met, now_text, title = got
        status[rule_id] = {"now": now_text, "met": bool(met), "title": title}
        if not rule.get("on", True):
            continue
        if met and rule_id not in fired:
            fired.add(rule_id)
            alerts.append((rule, f"{ICONS.get(rule.get('kind'), '🔔')} {title}", now_text))
        elif not met and value is not None:
            fired.discard(rule_id)
    return alerts, status


def needs_spaceports(rules):
    return any(r.get("on", True) and r.get("kind") == "spaceport_wait" for r in rules)


def _stock_now(stock, hours_per_game_day):
    qty, rate = _num(stock.get("qty")) or 0, _num(stock.get("rate")) or 0
    return f"{qty:,.1f} held, not falling" if rate >= 0 else f"runs out in {hours_text(qty / -rate * hours_per_game_day)}"


def choices(tapped, names):
    """What the editor offers: our stocks, places with crew, Earth's markets and the spaceports (once read).
    Stock and market entries carry the good, the place, where it is (body, or continent on Earth) and its value now,
    so the editor can ask for the good first, then where, and list only what matches."""
    _, scale = _game_now(tapped, None, 0)
    hours_per_game_day = 24.0 / scale
    stock = {(s.get("regionId"), s.get("goodId")): s for s in _stocks(tapped) if s.get("regionId") and s.get("goodId")}
    supplies = sorted({p.get("placeId") for p in _supplies(tapped) if p.get("placeId")})
    markets = {(m.get("regionId"), m.get("goodId")): m for m in _markets(tapped) if m.get("regionId") and m.get("goodId")}

    def market_now(m):
        stock_, target = _num(m.get("stock")), _num(m.get("target"))
        return f"{stock_ / target * 100:.0f}% of target" if stock_ is not None and target else ""

    return {
        "stock": [{"place": p, "good": g, "goodName": names.good(g), "placeName": names.place(p), "where": names.where(p),
                   "now": _stock_now(row, hours_per_game_day), "label": f"{names.good(g)} at {names.place(p)}"}
                  for (p, g), row in sorted(stock.items())],
        "supplies": [{"place": p, "label": names.place(p)} for p in supplies],
        "market": [{"region": r, "good": g, "goodName": names.good(g), "placeName": names.place(r), "where": names.where(r),
                    "now": market_now(row), "label": f"{names.good(g)} in {names.place(r)}"}
                   for (r, g), row in sorted(markets.items())],
        "spaceport": [{"spaceport": p.get("regionId"), "label": p.get("spaceport") or names.place(p.get("regionId"))}
                      for p in _ports(tapped) if p.get("regionId")],
    }
