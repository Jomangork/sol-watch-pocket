"""Headings for the bridge's alert kinds on the app's Alert types page.

ALERT_KINDS in sol_watch.py (1.08.00+) is grouped by comments, which the app's compiled copy of the bridge
doesn't keep, so the headings are copied here. A kind added later shows under "Other" until it's listed."""

GROUPS = (
    ("Bridge and API key", (
        "bridge_status", "bridge_update", "key_problem", "key_optional", "key_ok", "api_changed", "game_update",
        "heads_up",
    )),
    ("Research", (
        "research_summary", "research_started", "research_soon", "research_done", "research_stopped", "lab_idle",
        "research_queue_empty",
    )),
    ("Craft and launches", (
        "launch_summary", "craft_on_pad", "craft_launched", "craft_arrived", "craft_completed", "craft_failed",
        "craft_status", "craft_safe_mode", "craft_lost", "escape_capsule", "escape_capsule_gone",
    )),
    ("Mining and industry", (
        "mining_summary", "mining_order", "ore_body_empty", "industry_summary", "site_stopped", "site_slowed",
        "stock_running_out", "storage_filling", "vehicle_blocked", "elevator_booked", "elevator_soon",
        "elevator_arrived", "rail_laying", "rail_open", "rail_line_laying", "rail_line_open", "power_short",
        "power_restored", "life_support_short", "life_support_restored",
    )),
    ("Company", (
        "facility_upgraded", "upgrade_done", "queue_blocked", "queue_summary", "supplies_low", "supplies_summary",
        "daily_gift",
    )),
    ("Contracts", (
        "contract_board_summary", "contract_offer", "contract_repair", "contract_taken", "contract_history_summary",
        "contract_closed", "contract_list", "bounty", "rescue_contract", "rescue_under_way", "alliance_jobs",
        "alliance_job", "ship_order", "ship_order_update", "siege_contract", "siege_contract_ended",
    )),
    ("Defense and hazards", (
        "defense_summary", "asteroid_threat", "asteroid_outcome", "hazard_forecast", "hazard", "outpost_damaged",
        "damage", "debris_field",
    )),
    ("Fleets and battles", (
        "incoming_fleet", "battle_ours", "wreck_ours", "battle_elsewhere", "wreck_elsewhere", "our_battle",
        "our_battle_over", "our_duel", "our_duel_over", "our_siege", "our_siege_ended", "our_combat",
        "our_combat_over", "fleet_left", "fleet_rerouted", "fleet_soon", "fleet_landed",
    )),
    ("Factions, Earth and diplomacy", (
        "standings_summary", "standing_changed", "government_changed", "ballot", "ballot_closing",
        "policies_in_force", "shipbroker_summary", "shipbroker_listed", "clear_sky_data", "clear_sky_data_summary",
        "colony_to_name", "deal_offer", "deal_status", "deal_countered", "deal_expiring", "deal_shortfall",
        "agreement_new", "agreement_ended", "spy_caught", "our_spy_caught", "marked_hostile", "relay_lost",
        "codex_stopped", "market_corruption",
    )),
    ("News, records and places", (
        "badge_earned", "world_first", "tech_first", "record", "theft", "clear_sky", "pirates_hit_us",
        "pirate_activity", "derelict_salvaged", "salvage_target_taken", "faction_warship_sale", "market_event",
        "segment_cost_rise", "claim_takeover", "claim_lost", "new_places", "pad_goal", "derelict_found",
    )),
    ("Chat", (
        "chat", "alliance_chat",
    )),
    ("Public news (Lite)", (
        "lite_setup", "lite_status", "company_renamed", "bully_marked", "duel_record", "crew_lost", "news_raid",
        "news_hazard", "news_faction", "news_ambition", "news_faction_colony", "news_segment", "news_politics",
        "news_holiday", "news_strike", "news_clear_sky", "news_founded", "news_inactive", "news_alliance",
        "news_colony", "news_station", "news_outpost", "news_range_expansion", "news_pad_building", "news_ueg_built",
        "news_ueg_navy", "news_ueg_elevator", "news_badge_first", "news_project_honor", "news_market_event",
        "news_salvage", "news_abandoned", "news_launch_failure", "news_outpost_surrendered", "news_return",
        "news_terrorist_hunt", "news_frontier", "news_battle_begun", "news_attack_repelled", "news_siege_laid",
        "news_other",
    )),
)
