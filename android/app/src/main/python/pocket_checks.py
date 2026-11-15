"""The checks the app's Checks menu offers: one per bridge watcher, with recommended intervals.

This is the single list both the app (menu, data estimate) and pocket_runner use.
Intervals are seconds; 0 means off. `kib` is the measured gzip size of one read when the
data has changed (2026-11-13, bridge 1.07.12), headers included; unchanged reads cost ~1 KiB.
`feeds` names what else uses this check's data, so the menu can warn before turning it off.
"""
import json

GROUPS = ("Threats", "Company", "Contracts & factions", "Chat & news", "Background")

# (watcher class, group, title, what it alerts, recommended, saver, fast, kib, measured, feeds)
_ROWS = (
    ("FleetMapWatcher", "Threats", "Incoming fleets & battles",
     "Rival warships heading for our places, battles anywhere", 60, 60, 30, 2, True, ""),
    ("FleetsWatcher", "Threats", "Our battles & sieges",
     "Our battles, duels and sieges", 60, 120, 60, 1, True, "Incoming fleets (where our fleets are)"),
    ("CraftWatcher", "Threats", "Launches & craft",
     "Launches, arrivals, failures, escape capsules", 60, 120, 15, 1.5, True,
     "Blocked queues, flare safe mode, salvage"),
    ("DefenseWatcher", "Threats", "Hazards & debris",
     "Hazard forecasts and strikes, debris fields, asteroid threats", 120, 300, 60, 1.5, True,
     "Theft checks (tugs clearing debris)"),
    ("NewsWatcher", "Threats", "Raids & thefts",
     "Pirate raids, thefts near Earth, Clear Sky", 300, 600, 300, 2.4, True, ""),
    ("SegmentsWatcher", "Threats", "Segments & claims",
     "Tax rises, claim takeovers and losses", 600, 1800, 300, 2, True, ""),
    ("CompanyWatcher", "Company", "Company",
     "Queues, blocked launches, crew supplies, construction", 120, 300, 60, 7.1, True,
     "Incoming fleets (our places), standing advice, research queue"),
    ("IndustryWatcher", "Company", "Industry",
     "Stock running out, storage filling, production stalls, blocked vehicles", 300, 900, 300, 69, True,
     "Diplomacy goods check, walkout news"),
    ("ResearchWatcher", "Company", "Research",
     "Research started, finished, 5-minute warning, queue empty", 120, 300, 60, 1, True, "Company (queue alerts)"),
    ("MiningWatcher", "Company", "Mining",
     "Mining changes", 300, 900, 30, 0.8, True, ""),
    ("OreBodiesWatcher", "Company", "Ore reserves",
     "Ore running out", 300, 900, 30, 13, True, "Mining (asteroid names)"),
    ("PocketWidget", "Company", "Widget data",
     "Money and income for the home-screen widget (one small read); research and arrivals come from the other checks",
     300, 900, 60, 1.5, False, "The widget's money and income"),
    ("AccountWatcher", "Company", "Daily gift",
     "Daily login gift unclaimed", 600, 1800, 300, 1, True, ""),
    ("MarketWatcher", "Company", "Market corruption",
     "Earth's market corruption level changing (downloads the large market reply)", 3600, 21600, 3600, 125, True, ""),
    ("RecordsWatcher", "Company", "Badges & firsts",
     "Badges and firsts", 1800, 3600, 600, 17, True, ""),
    ("ContractsWatcher", "Contracts & factions", "Contract board",
     "Offers, ship orders, sieges against us, standing advice", 120, 300, 60, 3.7, True, ""),
    ("ContractHistoryWatcher", "Contracts & factions", "Contract results",
     "Contracts won, taken by others", 300, 600, 120, 1, True, ""),
    ("DiplomacyWatcher", "Contracts & factions", "Diplomacy",
     "Offers made to us, expiry warnings, agreements", 120, 300, 60, 5.5, True, ""),
    ("FactionsWatcher", "Contracts & factions", "Factions & Earth",
     "Standings, Halcyon Arms Exchange, government, ballot, colony naming", 600, 900, 300, 5.5, True,
     "Standing advice on contracts"),
    ("PadGoalWatcher", "Contracts & factions", "Pad goals",
     "Spaceports asking for a new pad's materials", 600, 1800, 300, 4.7, True, ""),
    ("AllianceWatcher", "Chat & news", "Alliance intel",
     "Spies caught", 600, 900, 300, 6.7, True, "Battles and hazards (who is an ally)"),
    ("DiscoveriesWatcher", "Chat & news", "Hostility & Codex",
     "Alliances marking us hostile, Codex recovery stopped", 600, 900, 300, 6, True,
     "Incoming fleets (who marked us hostile)"),
    ("ChatWatcher", "Chat & news", "Chat relay",
     "Global and alliance chat", 300, 3600, 15, 7.2, True, ""),
    ("DerelictWatcher", "Background", "Derelicts",
     "Other companies' derelicts (downloads the large craft map)", 0, 0, 3600, 57, True, ""),
    ("PlaceWatcher", "Background", "New places",
     "New landing sites and stations (place names for other alerts)", 21600, 21600, 21600, 11.6, True,
     "Place names in all alerts"),
    ("BridgeUpdateWatcher", "Background", "Bridge updates",
     "A newer sol_watch.py is published", 86400, 86400, 86400, 17, True, ""),
    ("ApiSpecWatcher", "Background", "API changes",
     "The SOL API changed", 86400, 86400, 3600, 15, True, ""),
    ("GameUpdateWatcher", "Background", "Game updates",
     "A new build of the game (only logged)", 86400, 86400, 300, 3, True, ""),
)

# Shortest interval allowed for each, and why: the menu shows the reason under the check, so nobody has to ask.
MIN_SECONDS = {"DerelictWatcher": 600, "MarketWatcher": 600}
MIN_WHY = {
    "DerelictWatcher": "Every 10 minutes at most: each read downloads the game's whole craft map (about 57 KB), "
                       "and the bridge itself refuses to read it more often.",
    "MarketWatcher": "Every 10 minutes at most: each read downloads Earth's whole market (about 125 KB), so every "
                     "15 seconds would use about 30 MB an hour.",
}
MIN_DEFAULT = 10
MAX_SECONDS = 86400
PRESETS = ("recommended", "saver", "fast")

CATALOG = tuple(
    {"id": cls, "group": group, "title": title, "alerts": alerts,
     "recommended": rec, "saver": saver, "fast": fast, "kib": kib, "measured": measured, "feeds": feeds,
     "min": MIN_SECONDS.get(cls, MIN_DEFAULT), "min_why": MIN_WHY.get(cls, "")}
    for cls, group, title, alerts, rec, saver, fast, kib, measured, feeds in _ROWS
)
IDS = tuple(row["id"] for row in CATALOG)
# Checks the app adds itself (not bridge watchers): id -> API path. pocket_runner reads them.
POCKET_FEEDS = {"PocketWidget": "/me"}


def preset(name):
    """{watcher class: seconds (0 = off)} for one of PRESETS."""
    return {row["id"]: row[name] for row in CATALOG}


def clamp(check_id, seconds):
    """0 stays off; anything else is kept within what the bridge allows."""
    seconds = int(seconds or 0)
    if seconds <= 0:
        return 0
    row = next((r for r in CATALOG if r["id"] == check_id), None)
    return max(row["min"] if row else MIN_DEFAULT, min(seconds, MAX_SECONDS))


def catalog_json():
    return json.dumps({"groups": GROUPS, "presets": PRESETS, "checks": CATALOG})
