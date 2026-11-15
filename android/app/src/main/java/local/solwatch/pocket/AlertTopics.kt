package local.solwatch.pocket

/**
 * The Alerts tab's topics: each tab, the filters beneath it, and the bridge's alert kinds (bridge 1.08.00+) in each.
 * A kind not listed here (one a newer bridge adds) goes to a tab by its heading on the Alert types page; an alert
 * without a kind (from before 0.8.2) by its old Discord channel.
 */
object AlertTopics {
    data class Sub(val name: String, val kinds: Set<String>)
    data class Tab(val name: String, val subs: List<Sub>)

    const val CHAT = "Chat"

    val TABS = listOf(
        Tab("Company", listOf(
            Sub("Research", setOf("research_summary", "research_started", "research_soon", "research_done",
                "research_stopped", "lab_idle", "research_queue_empty")),
            Sub("Craft", setOf("launch_summary", "craft_on_pad", "craft_launched", "craft_arrived",
                "craft_completed", "craft_failed", "craft_status", "craft_safe_mode", "craft_lost", "escape_capsule",
                "escape_capsule_gone")),
            Sub("Industry", setOf("mining_summary", "mining_order", "ore_body_empty", "industry_summary",
                "site_stopped", "site_slowed", "stock_running_out", "storage_filling", "vehicle_blocked",
                "power_short", "power_restored", "life_support_short", "life_support_restored", "elevator_booked",
                "elevator_soon", "elevator_arrived")),
            Sub("Upkeep", setOf("facility_upgraded", "upgrade_done", "queue_blocked", "queue_summary",
                "supplies_low", "supplies_summary", "daily_gift")),
            Sub("Claims", setOf("claim_takeover", "claim_lost", "segment_cost_rise", "relay_lost", "codex_stopped")),
        )),
        Tab("Fleets", listOf(
            Sub("Our fleets", setOf("fleet_left", "fleet_rerouted", "fleet_soon", "fleet_landed", "our_battle",
                "our_battle_over", "our_duel", "our_duel_over", "our_siege", "our_siege_ended", "our_combat",
                "our_combat_over")),
            Sub("Threats", setOf("incoming_fleet", "battle_ours", "wreck_ours", "theft", "pirates_hit_us",
                "clear_sky", "marked_hostile", "spy_caught", "our_spy_caught")),
            Sub("Hazards", setOf("defense_summary", "hazard_forecast", "hazard", "asteroid_threat",
                "asteroid_outcome", "outpost_damaged", "damage", "debris_field")),
            Sub("Elsewhere", setOf("battle_elsewhere", "wreck_elsewhere", "pirate_activity")),
        )),
        Tab("Contracts", listOf(
            Sub("Board", setOf("contract_board_summary", "contract_offer", "contract_repair", "contract_taken",
                "contract_list")),
            Sub("Alliance jobs", setOf("alliance_jobs", "alliance_job")),
            Sub("Ship orders", setOf("ship_order", "ship_order_update")),
            Sub("Bounties", setOf("bounty", "rescue_contract", "rescue_under_way")),
            Sub("Sieges on us", setOf("siege_contract", "siege_contract_ended")),
            Sub("Closed", setOf("contract_history_summary", "contract_closed")),
        )),
        Tab("Sol", listOf(
            Sub("Earth", setOf("standings_summary", "standing_changed", "government_changed", "ballot",
                "ballot_closing", "policies_in_force", "heads_up", "market_corruption", "colony_to_name")),
            Sub("Deals", setOf("deal_offer", "deal_status", "deal_countered", "deal_expiring", "deal_shortfall",
                "agreement_new", "agreement_ended")),
            Sub("Market", setOf("market_event", "faction_warship_sale", "shipbroker_summary", "shipbroker_listed")),
            Sub("Places", setOf("new_places", "pad_goal", "derelict_found", "derelict_salvaged",
                "salvage_target_taken")),
            Sub("Records", setOf("badge_earned", "world_first", "tech_first", "record", "clear_sky_data",
                "clear_sky_data_summary")),
        )),
        Tab("Chat", listOf(
            Sub("Global", setOf("chat")),
            Sub("Alliance", setOf("alliance_chat")),
        )),
        Tab("Bridge", listOf(
            Sub("Status", setOf("bridge_status")),
            Sub("API key", setOf("key_problem", "key_optional", "key_ok")),
            Sub("Updates", setOf("bridge_update", "api_changed", "game_update")),
        )),
    )

    // Alert types headings (pocket_kinds.py) -> tab, for kinds this table doesn't know yet.
    private val BY_HEADING = mapOf(
        "Bridge and API key" to "Bridge",
        "Research" to "Company", "Craft and launches" to "Company", "Mining and industry" to "Company", "Company" to "Company",
        "Contracts" to "Contracts",
        "Defense and hazards" to "Fleets", "Fleets and battles" to "Fleets",
        "Factions, Earth and diplomacy" to "Sol", "News, records and places" to "Sol",
        "Chat" to CHAT,
    )

    private val BY_KIND: Map<String, Pair<String, String>> =
        TABS.flatMap { tab -> tab.subs.flatMap { sub -> sub.kinds.map { it to (tab.name to sub.name) } } }.toMap()

    /**
     * (tab, filter) for an alert; the filter is "" when it isn't known. heading: the kind's heading on the
     * Alert types page, if the table doesn't list the kind.
     */
    fun of(kind: String, channel: String, priority: String, heading: String? = null): Pair<String, String> {
        BY_KIND[kind]?.let { return it }
        if (kind.isNotBlank()) return (BY_HEADING[heading] ?: "Sol") to ""
        return when {
            priority == "chat" -> CHAT to if (channel == "alliance") "Alliance" else "Global"
            channel == "general" -> "Contracts" to ""
            channel == "alliance" -> "Fleets" to ""
            else -> "Company" to ""
        }
    }

    fun knows(kind: String) = kind in BY_KIND

    /** "Contracts · Alliance jobs", for a notification's header. */
    fun label(topic: Pair<String, String>) = listOf(topic.first, topic.second).filter { it.isNotBlank() }.joinToString(" · ")

    fun subsOf(tab: String): List<String> = TABS.firstOrNull { it.name == tab }?.subs?.map { it.name }.orEmpty()
}
