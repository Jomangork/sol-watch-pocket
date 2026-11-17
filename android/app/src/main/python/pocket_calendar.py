"""The Calendar tab's entries, built from replies the bridge already reads (tapped, never read again).

Each entry: {"id", "day" (game day or None), "at" (real ms or None), "kind" ("world", "ours" or "threat"),
"title", "sub", "major", "about", "source", "details" [lines]}. "at" is None for something without a date
yet (an elevator still being built). Ids stay the same while the entry means the same thing, so the app
can keep a reminder on it.
"""
import re
import pocket_glance

# Bridge reads the calendar takes its dates from.
PATHS = ("/clock", "/company", "/industry", "/factions", "/ueg", "/defense", "/research", "/craft",
         "/reference/techs", "/reference/places", "/reference/bodies", "/fleets")  # /fleets: only for new rule dates
PAST_HOURS = 6  # an entry stays listed this long after its time, marked as past
RELAY_START_DAYS, RELAY_FULL_DAYS = 15, 30  # real days after the relay burn-in (game glossary)
ELEVATOR_NAMES = {"elevator": "Artsutanov Elevator", "tsiolkovsky_elevator": "Tsiolkovsky Elevator",
                  "pearson": "Pearson Elevator", "skyhook": "Skyhook"}
HAZARD_NAMES = {"flare": "Solar flare"}
ELEVATOR_IDS = ("elevator", "tsiolkovsky_elevator", "pearson")


KNOWN_RULE_DAYS = {("/industry", "power.rulesDay")}  # placed by hand above
# The landing and lift-off rules: built into the game's own code (not the API) as game day 12590, about 6 Dec 2026
# (bridge chat, BRIDGE-NOTES.md 2026-11-17). Only a game update can move it.
LANDING_RULES_DAY = 12590
RULE_DAY_RE = re.compile(r"(?:^|[a-z])[Rr]ules?(?:Start)?Day$")


def _our_gravity(tapped):
    """["Ganymede: 1.43 m/s²", …] for the landable worlds where we hold stock or crew, strongest pull first."""
    bodies = {b.get("id"): b for b in (tapped.get("/reference/bodies") or {}).get("bodies") or () if isinstance(b, dict)}
    places = {p.get("id"): p for p in (tapped.get("/reference/places") or {}).get("places") or () if isinstance(p, dict)}
    ours = {s.get("regionId") for s in ((tapped.get("/industry") or {}).get("industry") or {}).get("stock") or ()
            if isinstance(s, dict)}
    ours |= {p.get("placeId") for p in (tapped.get("/company") or {}).get("sustainment") or () if isinstance(p, dict)}
    pulls = {}
    for place in ours:
        body = bodies.get((places.get(place) or {}).get("bodyId"))
        gm, radius = _num((body or {}).get("gm")), _num((body or {}).get("radiusKm"))
        if not body or body.get("id") in ("earth", "venus") or not body.get("landable") or not gm or not radius:
            continue
        pulls[body.get("name") or body.get("id")] = gm / radius ** 2 * 1000
    return [f"{name}: {g:.2f} m/s²" for name, g in sorted(pulls.items(), key=lambda kv: -kv[1])]


def _rule_days(tapped, depth=3):
    """(API path, dotted key, game day) for every "...RulesDay" number in the tapped replies, a few levels deep."""
    found = []

    def walk(value, path, prefix, level):
        if level > depth or not isinstance(value, dict):
            return
        for key, item in value.items():
            dotted = f"{prefix}{key}"
            if isinstance(item, (int, float)) and not isinstance(item, bool) and RULE_DAY_RE.search(str(key)):
                found.append((path, dotted, item))
            elif isinstance(item, dict):
                walk(item, path, dotted + ".", level + 1)

    for path, body in tapped.items():
        walk(body, path, "", 0)
    return found


def _tidy_field(key):
    """'landing.rulesDay' -> 'Landing', 'liftRulesDay' -> 'Lift' (the part that names the rule)."""
    last = key.split(".")
    name = re.sub(r"[Rr]ules?(?:Start)?Day$", "", last[-1]) or (last[-2] if len(last) > 1 else "rules")
    words = re.sub(r"(?<!^)(?=[A-Z])", " ", name).replace("_", " ").strip()
    return words[:1].upper() + words[1:].lower() if words else "Rules"


def _num(value):
    return value if isinstance(value, (int, float)) and not isinstance(value, bool) else None


class _Clock:
    def __init__(self, clock_body):
        clock = (clock_body or {}).get("clock") or {}
        self.epoch_ms = _num(clock.get("epochRealMs"))
        self.epoch_day = _num(clock.get("epochGameDay"))
        self.scale = _num(clock.get("timeScale")) or 24.0

    @property
    def ok(self):
        return self.epoch_ms is not None and self.epoch_day is not None

    def ms(self, day):
        return int(self.epoch_ms + (day - self.epoch_day) / self.scale * 86_400_000)

    def day(self, ms):
        return self.epoch_day + (ms - self.epoch_ms) / 86_400_000 * self.scale


def _name(item_id, tapped):
    return pocket_glance._name(item_id, tapped.get("/reference/places"), tapped.get("/reference/bodies"))


def build(tapped, now_ms):
    """Entries sorted by time (undated ones last), from the tapped replies. [] until /clock has been read."""
    clock = _Clock(tapped.get("/clock"))
    if not clock.ok:
        return []
    out = []

    def add(entry_id, day, kind, title, sub="", major=False, about="", source="", details=(), undated=False):
        day = _num(day)
        if day is None and not undated:  # the game hasn't set it (or the reply hasn't been read yet)
            return
        at = clock.ms(day) if day is not None else None
        if at is not None and at < now_ms - PAST_HOURS * 3_600_000:
            return
        out.append({"id": entry_id, "day": day, "at": at, "kind": kind, "title": title, "sub": sub,
                    "major": major, "about": about, "source": source, "details": list(details)})

    company = tapped.get("/company") or {}
    industry = tapped.get("/industry") or {}
    factions = tapped.get("/factions") or {}
    ueg = tapped.get("/ueg") or {}
    defense = tapped.get("/defense") or {}

    # ---- world: rule changes ----
    power = industry.get("power") or {}
    places = []
    for p in power.get("places") or ():
        share, life = _num(p.get("share")), _num(p.get("lifeShare"))
        if share is None or life is None:
            continue
        name = _name(p.get("placeId"), tapped)
        if life < 0.999:
            places.append(f"{name}: life support {life:.0%}, operations {share:.0%}")
        elif share < 0.999:
            places.append(f"{name}: operations {share:.0%}")
        else:
            places.append(f"{name}: full power")
    add("power_rules", power.get("rulesDay"), "world", "Power rules start",
        "Life support first when power runs short", major=True,
        about="A place off Earth whose plants and battery fall short serves life support first, and its "
              "operations share what's left. If life support runs short, its people ration, then leave or die. "
              "Keep enriched uranium stocked at each place: the fuel our fission plants were given runs out in time.",
        source="Industry check", details=places)

    # Landing and lift-off: gravity of the worlds where we have places, from /reference/bodies (gm / r² × 1000).
    add("landing_rules", LANDING_RULES_DAY, "world", "Landing and lift-off rules",
        "Ships too weak for a world's gravity hold orbit instead", major=True,
        about="A ship lands on a world, or lifts off from it, on its own drive only when its lift (thrust over its mass, so "
              "a full hold lifts less) beats the world's gravity. A fleet lands only if its weakest ship can; one that "
              "can't holds orbit over the place, still defends it and pays no climb. Today this applies only to faction "
              "colonies, Moon places and faction outposts near Earth and the Moon. From this date it covers every surface "
              "place, our own outposts included, and tugs and freighters too. Ships towed up or down aren't affected; "
              "Earth and Venus's cloud cities don't count. The game shows each ship's lift; the API doesn't yet.",
        source="The game's own rules (a fixed date, not in the API)", details=_our_gravity(tapped))

    # A rule change the app doesn't know yet: any "...RulesDay" or "rulesDay" field in a reply the checks read, so
    # a new game rule shows up the day it appears, before an app update names it properly.
    for path, key, day in _rule_days(tapped):
        if (path, key) in KNOWN_RULE_DAYS:
            continue
        label = _tidy_field(key)
        add(f"rules:{path}:{key}", day, "world", f"New game rule: {label}", "Rule change found in the game's data",
            major=True, about=f"The game gives a date for this rule ({path} → {key}), but this app doesn't know it "
                              "yet. Check the game's help for what changes.", source=f"{path} (new field)")

    burn_in = _num(company.get("relayBurnInDay"))
    if burn_in is not None:
        relay_about = ("Craft outside our relay coverage earn and find less, telescopes there track nothing, and "
                       "warships stationed there tire faster. An orbiter on the relay task, a station or base, an "
                       "ally's relay or Relay access from a faction covers a system.")
        add("relay_rules_start", burn_in + RELAY_START_DAYS * clock.scale, "world", "Relay rules start phasing in",
            "In full 15 real days later", major=True, about=relay_about, source="Company check")
        add("relay_rules_full", burn_in + RELAY_FULL_DAYS * clock.scale, "world", "Relay rules in full",
            "", major=True, about=relay_about, source="Company check")
    add("module_parts", company.get("modulePartsDay"), "world", "New ship module parts",
        "Modules over the Standard take new parts", major=True,
        about="From now, ship modules over the Standard take sensor suites, magnets or laser comm terminals "
              "instead of avionics or power units.", source="Company check")

    # ---- world: Earth's ballot and the Security Council ----
    ballot = factions.get("ballot") or {}
    mine = factions.get("ballotMine") or {}
    year = ballot.get("year") or ""
    voted = mine.get("vote")
    vote_note = ("We voted" if voted else "We haven't voted yet") if mine.get("canVote") or voted else ""
    ballot_about = ("Earth's ballot runs once a game year: nominating for 5 real days, then voting for 7. "
                    "Three policies are on it; a winning policy changes two UEG numbers.")
    add(f"ballot_voting:{year}", ballot.get("votingDay"), "world", "Ballot voting opens",
        "Seven real days to vote", about=ballot_about, source="Factions check")
    add(f"ballot_closes:{year}", ballot.get("closesDay"), "world", "Earth's ballot closes",
        vote_note, major=True, about=ballot_about, source="Factions check")
    add(f"ballot_opens:{ballot.get('nextOpensDay')}", ballot.get("nextOpensDay"), "world",
        "Next ballot opens for nominations", about=ballot_about, source="Factions check")
    government = factions.get("government") or {}
    add(f"council:{government.get('nextSessionDay')}", government.get("nextSessionDay"), "world",
        "Security Council session", "UEG government", source="Factions check")

    # ---- world: elevators being built (no date until the game gives one) ----
    for project in ueg.get("projects") or ():
        station = project.get("stationId")
        if station in ELEVATOR_IDS and project.get("builtDay") is None:
            name = ELEVATOR_NAMES.get(station) or str(station).replace("_", " ").title()
            add(f"elevator_built:{station}", None, "world", f"{name} opens", "Under construction",
                about="Built from deliveries to its pools; the game gives no opening date.", source="Pad goals check", undated=True)

    # ---- threats ----
    for incident in defense.get("hazardIncidents") or ():
        hazard = HAZARD_NAMES.get(incident.get("hazardId")) or str(incident.get("hazardId") or "Hazard").title()
        size = incident.get("size")
        end = _num(incident.get("endDay"))
        lasts = f" · lasts {(end - incident['startDay']) / clock.scale * 24:.0f} h" \
            if end is not None and _num(incident.get("startDay")) is not None else ""
        add(f"hazard:{incident.get('id')}", incident.get("startDay"), "threat", f"{hazard} hits",
            (f"{size.title()} · " if isinstance(size, str) else "") + "Hazard forecast" + lasts,
            source="Hazards & debris check")
    for threat in defense.get("threats") or ():
        if threat.get("outcome") is None:
            add(f"asteroid:{threat.get('id')}", threat.get("arrivalDay"), "threat",
                f"Asteroid {threat.get('name') or ''} arrives".replace("  ", " "),
                f"{threat['diameterM']} m" if _num(threat.get("diameterM")) else "", source="Hazards & debris check")

    # ---- ours ----
    research = pocket_glance._research(tapped.get("/research"), tapped.get("/reference/techs"), clock.day(now_ms))
    if research:
        add(f"research:{research['name']}", research.get("doneDay"), "ours", f"Research done: {research['name']}",
            "Research", source="Research check")
    for bill in company.get("workBills") or ():
        what = str(bill.get("what") or bill.get("kind") or "Construction")
        add(f"build:{bill.get('kind')}:{bill.get('ref')}:{bill.get('readyDay')}", bill.get("readyDay"), "ours",
            f"Ready: {what[:1].upper()}{what[1:]}", str(bill.get("where") or ""), source="Company check")
    for trip in industry.get("elevatorTrips") or ():
        if isinstance(trip, dict) and trip.get("id") is not None:
            add(f"elevator_trip:{trip['id']}", trip.get("arriveDay"), "ours", "Elevator cargo arrives",
                ELEVATOR_NAMES.get(trip.get("elevatorId"), "Space elevator"), source="Industry check")
    me = (tapped.get("/me") or {}).get("id")
    for mission in (tapped.get("/craft") or {}).get("missions") or ():
        if (isinstance(mission, dict) and me is not None and mission.get("companyId") == me
                and not mission.get("outcome")):
            add(f"craft:{mission.get('id')}:{mission.get('arriveDay')}", mission.get("arriveDay"), "ours",
                f"{mission.get('name') or 'Craft'} arrives", f"At {_name(mission.get('destinationId'), tapped)}",
                source="Launches & craft check")
    for deal in factions.get("deals") or ():
        if deal.get("status") == "open":
            party = deal.get("with") or {}
            add(f"deal:{deal.get('id')}", deal.get("expiresDay"), "ours", "Diplomacy offer expires",
                f"With {party.get('name') or party.get('id') or 'another party'}", source="Diplomacy check")

    out.sort(key=lambda e: (e["at"] is None, e["at"] or 0))
    return out
