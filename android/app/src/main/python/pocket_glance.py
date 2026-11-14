"""The home-screen widget's summary, built from data the bridge already reads.

The game's own /widget endpoint only answers the game's own widget, so instead:
money from /me (the one extra read, at the "Widget data" interval), and research, arrivals
and the clock from the bridge's own /research, /craft and /clock reads (tapped, not re-read).
The result keeps /widget's field names, so the app reads either the same way.
"""


def _rows(table):
    """Reference tables come as {"rulesVersion": …, "places": [...]}; older ones as a bare list."""
    if isinstance(table, dict):
        table = next((v for v in table.values() if isinstance(v, list)), [])
    return table or ()


def _name(item_id, *tables):
    for table in tables:
        for row in _rows(table):
            if isinstance(row, dict) and row.get("id") == item_id:
                return row.get("name") or item_id
    if isinstance(item_id, str) and item_id.islower():  # "churyumov_gerasimenko" -> "Churyumov Gerasimenko"
        return item_id.replace("_", " ").title()
    return item_id


def _research(research, techs, game_day):
    job = (research or {}).get("research")
    if not job:
        return None
    tech_id = str(job.get("techId") or "")
    name = _name(tech_id, techs)
    if tech_id.startswith("investigation:"):
        inv_id = tech_id.split(":", 1)[1]
        mission = next((i.get("missionName") for i in (research or {}).get("investigations") or ()
                        if str(i.get("id")) == inv_id), None)
        name = f"Failure investigation: {mission}" if mission else "Failure investigation"
    start, done = job.get("startDay"), job.get("completeDay")
    progress = None
    if isinstance(start, (int, float)) and isinstance(done, (int, float)) and done > start and game_day is not None:
        progress = min(1.0, max(0.0, (game_day - start) / (done - start)))
    return {"name": name, "progress": progress, "doneDay": done}


def _trips(craft, our_id, places, bodies, game_day):
    if game_day is None:
        return None
    flying = [m for m in (craft or {}).get("missions") or ()
              if isinstance(m, dict) and m.get("companyId") == our_id and not m.get("outcome")
              and isinstance(m.get("arriveDay"), (int, float)) and m["arriveDay"] > game_day
              and (m.get("launchDay") is None or m["launchDay"] <= game_day)]
    flying.sort(key=lambda m: m["arriveDay"])
    return {
        "underWay": len(flying),
        "next": [{"name": m.get("name"), "to": _name(m.get("destinationId"), places, bodies), "day": m["arriveDay"]}
                 for m in flying[:3]],
    }


def summarize(me, research=None, craft=None, clock=None, techs=None, places=None, bodies=None):
    """A /widget-shaped dict, or None without /me."""
    if not isinstance(me, dict):
        return None
    clock = clock or {}
    game_day = clock.get("gameDay", me.get("gameDay"))
    time_scale = (clock.get("clock") or {}).get("timeScale") or 24
    money = me.get("money")
    rate = me.get("incomeRate")
    settled = me.get("settledDay")
    if all(isinstance(v, (int, float)) for v in (money, rate, settled, game_day)):
        money = money + rate * (game_day - settled)  # as /me's own description says
    return {
        "company": me.get("name") or "",
        "gameDay": game_day,
        "timeScale": time_scale,
        "money": money,
        "incomeRate": rate,
        "rp": me.get("rp"),
        "rpRate": me.get("rpRate"),
        "research": _research(research, techs, game_day),
        "trips": _trips(craft, me.get("id"), places, bodies, game_day),
    }
