/**
 *  Dreamehome Vacuum Driver
 *
 *  Hubitat Elevation Child Driver for Dreame / MOVA / Trouver robot vacuums
 *  via the Dreamehome Vacuum Manager parent app (HTTPS + MQTT).
 *
 *  Ported from Tasshack/dreame-vacuum (Python / Home Assistant):
 *  https://github.com/Tasshack/dreame-vacuum
 *
 *  Licensed under the MIT License.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static final String DRIVER_VERSION = "1.0.0"

// ============================================================================
// MIoT SIID / PIID / AIID Constants (from dreame/types.py)
// ============================================================================

@Field static final List<Map> CORE_PROPERTIES = [
    // State, Error, Battery, Charging
    [key: "state",                  siid: 2,  piid: 1],
    [key: "error",                  siid: 2,  piid: 2],
    [key: "battery",                siid: 3,  piid: 1],
    [key: "chargingStatus",         siid: 3,  piid: 2],
    // Status, Cleaning telemetry, Modes
    [key: "status",                 siid: 4,  piid: 1],
    [key: "cleaningTime",           siid: 4,  piid: 2],
    [key: "cleanedArea",            siid: 4,  piid: 3],
    [key: "suctionLevel",           siid: 4,  piid: 4],
    [key: "waterVolume",            siid: 4,  piid: 5],
    [key: "waterTank",              siid: 4,  piid: 6],
    [key: "taskStatus",             siid: 4,  piid: 7],
    [key: "cleaningMode",           siid: 4,  piid: 23],
    [key: "selfWashBaseStatus",     siid: 4,  piid: 25],
    [key: "childLock",              siid: 4,  piid: 27],
    [key: "cleaningProgress",       siid: 4,  piid: 63],
    // DND, Volume, Totals
    [key: "dnd",                    siid: 5,  piid: 1],
    [key: "volume",                 siid: 7,  piid: 1],
    [key: "totalCleaningTime",      siid: 12, piid: 2],
    [key: "cleaningCount",          siid: 12, piid: 3],
    [key: "totalCleanedArea",       siid: 12, piid: 4],
    // Auto-empty & Wetness
    [key: "autoEmptyStatus",        siid: 15, piid: 5],
    [key: "wetnessLevel",           siid: 28, piid: 1],
    // Consumables
    [key: "mainBrushTimeLeft",      siid: 9,  piid: 1],
    [key: "mainBrushLeft",          siid: 9,  piid: 2],
    [key: "sideBrushTimeLeft",      siid: 10, piid: 1],
    [key: "sideBrushLeft",          siid: 10, piid: 2],
    [key: "filterLeft",             siid: 11, piid: 1],
    [key: "filterTimeLeft",         siid: 11, piid: 2],
    [key: "sensorDirtyLeft",        siid: 16, piid: 1],
    [key: "mopPadLeft",             siid: 18, piid: 1],
    [key: "silverIonLeft",          siid: 19, piid: 2],
    [key: "detergentLeft",          siid: 20, piid: 1],
    // Station tank / bag status
    [key: "cleanWaterTankStatus",   siid: 27, piid: 1],
    [key: "dirtyWaterTankStatus",   siid: 27, piid: 2],
    [key: "dustBagStatus",          siid: 27, piid: 3],
    [key: "detergentStatus",        siid: 27, piid: 4]
]

@Field static final Map<Integer, String> STATE_MAP = [
    1  : "sweeping",
    2  : "idle",
    3  : "paused",
    4  : "error",
    5  : "returning",
    6  : "charging",
    7  : "mopping",
    8  : "drying",
    9  : "washing",
    10 : "returning_to_wash",
    11 : "building_map",
    12 : "sweeping_and_mopping",
    13 : "charging_completed",
    14 : "upgrading",
    15 : "clean_summon",
    16 : "station_reset",
    17 : "returning_install_mop",
    18 : "returning_remove_mop",
    19 : "water_check",
    20 : "clean_add_water",
    21 : "washing_paused",
    22 : "auto_emptying",
    23 : "remote_control",
    24 : "smart_charging",
    25 : "second_cleaning",
    26 : "human_following",
    27 : "spot_cleaning",
    28 : "returning_auto_empty",
    29 : "waiting_for_task",
    30 : "station_cleaning",
    31 : "returning_to_drain",
    32 : "draining",
    33 : "auto_water_draining",
    34 : "emptying",
    35 : "dust_bag_drying",
    36 : "dust_bag_drying_paused"
]

@Field static final Map<Integer, String> STATUS_MAP = [
    0  : "idle",
    1  : "paused",
    2  : "cleaning",
    3  : "back_home",
    4  : "partial_cleaning",
    5  : "follow_wall",
    6  : "charging",
    7  : "ota",
    10 : "power_off",
    12 : "error",
    13 : "remote_control",
    14 : "sleeping",
    17 : "standby",
    18 : "segment_cleaning",
    19 : "zone_cleaning",
    20 : "spot_cleaning",
    21 : "fast_mapping",
    22 : "cruising_path",
    23 : "cruising_point",
    24 : "summon_clean",
    25 : "shortcut"
]

@Field static final Map<Integer, String> TASK_STATUS_MAP = [
    0  : "completed",
    1  : "auto_cleaning",
    2  : "zone_cleaning",
    3  : "segment_cleaning",
    4  : "spot_cleaning",
    5  : "fast_mapping",
    6  : "auto_cleaning_paused",
    7  : "zone_cleaning_paused",
    8  : "segment_cleaning_paused",
    9  : "spot_cleaning_paused",
    11 : "docking_paused",
    12 : "mopping_paused",
    13 : "segment_mopping_paused",
    14 : "zone_mopping_paused",
    15 : "auto_mopping_paused"
]

@Field static final Map<Integer, String> CHARGING_STATUS_MAP = [
    1: "charging",
    2: "not_charging",
    3: "charging_completed",
    5: "return_to_charge"
]

@Field static final Map<Integer, String> SUCTION_LEVEL_TO_NAME = [
    0: "Quiet",
    1: "Standard",
    2: "Strong",
    3: "Turbo"
]

@Field static final Map<String, Integer> SUCTION_NAME_TO_LEVEL = [
    "Quiet"   : 0,
    "Standard": 1,
    "Strong"  : 2,
    "Turbo"   : 3
]

@Field static final Map<Integer, String> WATER_VOLUME_TO_NAME = [
    1: "Low",
    2: "Medium",
    3: "High"
]

@Field static final Map<String, Integer> WATER_NAME_TO_VOLUME = [
    "Low"   : 1,
    "Medium": 2,
    "High"  : 3
]

@Field static final Map<Integer, String> CLEANING_MODE_TO_NAME = [
    0: "Sweeping",
    1: "Mopping",
    2: "Sweeping and Mopping",
    3: "Mopping after Sweeping"
]

@Field static final Map<String, Integer> CLEANING_NAME_TO_MODE = [
    "Sweeping"              : 0,
    "Mopping"               : 1,
    "Sweeping and Mopping"  : 2,
    "Mopping after Sweeping": 3
]

@Field static final Map<Integer, String> SELF_WASH_STATUS_MAP = [
    0: "idle",
    1: "washing",
    2: "drying",
    3: "returning",
    4: "paused",
    5: "clean_add_water",
    6: "returning_to_drain",
    7: "returning_for_dry_mop"
]

@Field static final Map<Integer, String> AUTO_EMPTY_STATUS_MAP = [
    0: "idle",
    1: "active",
    2: "not_performed",
    3: "returning"
]

@Field static final Map<Integer, String> ERROR_MAP = [
    0   : "No Error",
    1   : "Drop sensor triggered",
    2   : "Cliff sensor error",
    3   : "Bumper stuck",
    8   : "Dustbin not installed",
    9   : "Water tank not installed",
    10  : "Water tank empty",
    11  : "Dustbin full",
    12  : "Main brush tangled",
    13  : "Side brush tangled",
    14  : "Fan error",
    15  : "Left wheel motor error",
    16  : "Right wheel motor error",
    20  : "Low battery",
    21  : "Charging fault",
    47  : "Robot blocked",
    48  : "LDS laser sensor error",
    51  : "Filter blocked",
    59  : "Robot in no-go zone",
    76  : "Dirty water tank not installed",
    80  : "Robot stuck",
    86  : "Dirty water tank full",
    101 : "Station dustbin full",
    105 : "Station clean water tank issue",
    106 : "Station dirty water tank issue",
    107 : "Station clean water tank empty",
    117 : "Station disconnected",
    121 : "Dust bag full",
    128 : "Dock error",
    1000: "Return to dock failed"
]

@Field static final Map<String, Map> RESET_CONSUMABLE_ACTIONS = [
    "Main Brush": [siid: 9,  aiid: 1],
    "Side Brush": [siid: 10, aiid: 1],
    "Filter"    : [siid: 11, aiid: 1],
    "Sensor"    : [siid: 16, aiid: 1],
    "Mop Pad"   : [siid: 18, aiid: 1],
    "Silver Ion": [siid: 19, aiid: 1],
    "Detergent" : [siid: 20, aiid: 1]
]

@Field static final Set<String> ACTIVE_STATES = [
    "sweeping", "mopping", "sweeping_and_mopping", "spot_cleaning",
    "second_cleaning", "clean_summon", "building_map"
] as Set

// ============================================================================
// Metadata
// ============================================================================

metadata {
    definition(
        name: "Dreamehome Vacuum",
        namespace: "dpburek",
        author: "DPBurek (ported from Tasshack/dreame-vacuum)",
        importUrl: "https://raw.githubusercontent.com/DPBurek/dreame-vacuum/master/drivers/dreame-vacuum-driver.groovy"
    ) {
        capability "Actuator"
        capability "Battery"
        capability "Switch"
        capability "Consumable"
        capability "Refresh"
        capability "Initialize"

        // Core status attributes
        attribute "state", "string"
        attribute "status", "string"
        attribute "taskStatus", "string"
        attribute "chargingStatus", "string"
        attribute "errorCode", "number"
        attribute "errorDescription", "string"
        attribute "online", "string"
        attribute "mqttStatus", "string"

        // Cleaning telemetry
        attribute "cleaningTime", "number"
        attribute "cleanedArea", "number"
        attribute "cleaningProgress", "number"
        attribute "totalCleaningTime", "number"
        attribute "totalCleanedArea", "number"
        attribute "cleaningCount", "number"

        // Settings & modes
        attribute "suctionLevel", "string"
        attribute "waterVolume", "string"
        attribute "cleaningMode", "string"
        attribute "wetnessLevel", "number"
        attribute "selfWashBaseStatus", "string"
        attribute "autoEmptyStatus", "string"
        attribute "dndEnabled", "string"
        attribute "childLock", "string"
        attribute "volume", "number"

        // Consumables
        attribute "mainBrushLeft", "number"
        attribute "mainBrushTimeLeft", "number"
        attribute "sideBrushLeft", "number"
        attribute "sideBrushTimeLeft", "number"
        attribute "filterLeft", "number"
        attribute "filterTimeLeft", "number"
        attribute "sensorDirtyLeft", "number"
        attribute "mopPadLeft", "number"
        attribute "silverIonLeft", "number"
        attribute "detergentLeft", "number"

        // Station tanks / bag
        attribute "cleanWaterTankStatus", "number"
        attribute "dirtyWaterTankStatus", "number"
        attribute "dustBagStatus", "number"
        attribute "detergentStatus", "number"

        attribute "lastUpdated", "string"

        // Commands
        command "start"
        command "pause"
        command "stop"
        command "returnToBase"
        command "locate"

        command "cleanRooms", [
            [name: "roomIds*", type: "STRING", description: "Comma-separated room/segment IDs (e.g. 1,2,3)"],
            [name: "repeats", type: "NUMBER", description: "Cleaning passes (1-3, default 1)"],
            [name: "suctionLevel", type: "ENUM", constraints: ["", "Quiet", "Standard", "Strong", "Turbo"], description: "Optional suction override"],
            [name: "waterVolume", type: "ENUM", constraints: ["", "Low", "Medium", "High"], description: "Optional water volume override"]
        ]

        command "cleanZone", [
            [name: "coordinates*", type: "STRING", description: "Zone x1,y1,x2,y2 (separate multiple zones with ';')"],
            [name: "repeats", type: "NUMBER", description: "Cleaning passes (1-3, default 1)"]
        ]

        command "setSuctionLevel", [
            [name: "level*", type: "ENUM", constraints: ["Quiet", "Standard", "Strong", "Turbo"]]
        ]
        command "setWaterVolume", [
            [name: "volume*", type: "ENUM", constraints: ["Low", "Medium", "High"]]
        ]
        command "setCleaningMode", [
            [name: "mode*", type: "ENUM", constraints: ["Sweeping", "Mopping", "Sweeping and Mopping", "Mopping after Sweeping"]]
        ]
        command "setWetnessLevel", [
            [name: "level*", type: "NUMBER", description: "Mop wetness level (1-32, for supported models)"]
        ]

        command "startAutoEmpty"
        command "startWashing"
        command "pauseWashing"
        command "startDrying"
        command "stopDrying"

        command "setDnd", [
            [name: "enabled*", type: "ENUM", constraints: ["true", "false"]]
        ]
        command "setChildLock", [
            [name: "enabled*", type: "ENUM", constraints: ["true", "false"]]
        ]
        command "setVolume", [
            [name: "level*", type: "NUMBER", description: "Voice prompt volume (0-100)"]
        ]
        command "resetConsumable", [
            [name: "consumable*", type: "ENUM", constraints: ["Main Brush", "Side Brush", "Filter", "Sensor", "Mop Pad", "Silver Ion", "Detergent"]]
        ]

        command "connectMqtt"
        command "disconnectMqtt"
    }

    preferences {
        input name: "consumableLowThreshold", type: "number", title: "Consumable Low Warning Threshold (%)", defaultValue: 10, range: "1..50"
        input name: "debugLogging", type: "bool", title: "Enable Debug Logging", defaultValue: false
        input name: "infoLogging", type: "bool", title: "Enable Description Text Logging", defaultValue: true
    }
}

// ============================================================================
// Lifecycle & Metadata Sync
// ============================================================================

void installed() {
    logInfo "Installed Dreamehome Vacuum driver v${DRIVER_VERSION}"
    sendEvent(name: "mqttStatus", value: "disconnected")
    sendEvent(name: "online", value: "unknown")
}

void updated() {
    logInfo "Updated preferences for ${device.displayName}"
}

void uninstalled() {
    disconnectMqtt()
}

void initialize() {
    logInfo "Initializing ${device.displayName}"
    refresh()
    if (getDataValue("enableMqtt") == "true") {
        runIn(2, "connectMqtt")
    } else {
        disconnectMqtt()
        sendEvent(name: "mqttStatus", value: "disabled")
    }
}

void updateDeviceMetadata(Map meta) {
    if (meta.did)        updateDataValue("did", meta.did.toString())
    if (meta.model)      updateDataValue("model", meta.model.toString())
    if (meta.mac)        updateDataValue("mac", meta.mac.toString())
    if (meta.masterUid)  updateDataValue("masterUid", meta.masterUid.toString())
    if (meta.bindDomain) updateDataValue("bindDomain", meta.bindDomain.toString())
    if (meta.country)    updateDataValue("country", meta.country.toString())
    updateDataValue("enableMqtt", (meta.enableMqtt == true).toString())
    initialize()
}

// ============================================================================
// Core Vacuum Commands
// ============================================================================

void on() {
    start()
}

void off() {
    returnToBase()
}

void start() {
    logInfo "Starting cleaning"
    def res = callAction(2, 1)
    if (res != null) {
        sendEvent(name: "switch", value: "on")
        runIn(3, "refresh")
    }
}

void pause() {
    logInfo "Pausing cleaning"
    def res = callAction(2, 2)
    if (res != null) {
        runIn(3, "refresh")
    }
}

void stop() {
    logInfo "Stopping cleaning"
    def res = callAction(4, 2)
    if (res != null) {
        sendEvent(name: "switch", value: "off")
        runIn(3, "refresh")
    }
}

void returnToBase() {
    logInfo "Returning to dock"
    def res = callAction(3, 1)
    if (res != null) {
        sendEvent(name: "switch", value: "off")
        runIn(3, "refresh")
    }
}

void locate() {
    logInfo "Locating vacuum"
    callAction(7, 1)
}

void cleanRooms(String roomIds, BigDecimal repeats = 1, String suctionOverride = "", String waterOverride = "") {
    if (!roomIds?.trim()) {
        logWarn "cleanRooms called without roomIds"
        return
    }

    List<Integer> segments = roomIds.split(/[\s,]+/).findAll { it.isInteger() }.collect { it.toInteger() }
    if (segments.isEmpty()) {
        logWarn "cleanRooms: no valid integer room IDs found in '${roomIds}'"
        return
    }

    int repeatCount = Math.max(1, Math.min(3, (repeats ?: 1).toInteger()))
    int fan = SUCTION_NAME_TO_LEVEL[suctionOverride] != null ? SUCTION_NAME_TO_LEVEL[suctionOverride] : getCurrentSuctionInt()
    int water = WATER_NAME_TO_VOLUME[waterOverride] != null ? WATER_NAME_TO_VOLUME[waterOverride] : getCurrentWaterInt()

    List<List<Integer>> cleanList = segments.collect { Integer segId ->
        [segId, repeatCount, fan, water, 1]
    }

    String cleanJson = JsonOutput.toJson([selects: cleanList])
    logInfo "Starting room cleaning for segments ${segments} (repeats=${repeatCount}, suction=${fan}, water=${water})"

    List<Map> actionIn = [
        [piid: 1,  value: 18],       // STATUS = SEGMENT_CLEANING (18)
        [piid: 10, value: cleanJson] // CLEANING_PROPERTIES
    ]
    def res = callAction(4, 1, actionIn)
    if (res != null) {
        sendEvent(name: "switch", value: "on")
        runIn(4, "refresh")
    }
}

void cleanZone(String coordinates, BigDecimal repeats = 1) {
    if (!coordinates?.trim()) {
        logWarn "cleanZone called without coordinates"
        return
    }

    int repeatCount = Math.max(1, Math.min(3, (repeats ?: 1).toInteger()))
    int fan = getCurrentSuctionInt()
    int water = getCurrentWaterInt()

    List<List<Integer>> areas = []
    coordinates.split(";").each { String zoneStr ->
        List<Integer> coords = zoneStr.trim().split(/[\s,]+/).findAll { it.isInteger() }.collect { it.toInteger() }
        if (coords.size() == 4) {
            areas << [coords[0], coords[1], coords[2], coords[3], repeatCount, fan, water]
        } else {
            logWarn "Ignoring invalid zone '${zoneStr}' (expected 4 integers: x1,y1,x2,y2)"
        }
    }

    if (areas.isEmpty()) {
        logWarn "cleanZone: no valid zones parsed from '${coordinates}'"
        return
    }

    String areaJson = JsonOutput.toJson([areas: areas])
    logInfo "Starting zone cleaning: ${areaJson}"

    List<Map> actionIn = [
        [piid: 1,  value: 19],      // STATUS = ZONE_CLEANING (19)
        [piid: 10, value: areaJson] // CLEANING_PROPERTIES
    ]
    def res = callAction(4, 1, actionIn)
    if (res != null) {
        sendEvent(name: "switch", value: "on")
        runIn(4, "refresh")
    }
}

void setSuctionLevel(String level) {
    Integer val = SUCTION_NAME_TO_LEVEL[level]
    if (val == null) {
        logWarn "Invalid suction level: ${level}"
        return
    }
    logInfo "Setting suction level to ${level} (${val})"
    if (setProperty(4, 4, val) != null) {
        sendEvent(name: "suctionLevel", value: level)
    }
}

void setWaterVolume(String volume) {
    Integer val = WATER_NAME_TO_VOLUME[volume]
    if (val == null) {
        logWarn "Invalid water volume: ${volume}"
        return
    }
    logInfo "Setting water volume to ${volume} (${val})"
    if (setProperty(4, 5, val) != null) {
        sendEvent(name: "waterVolume", value: volume)
    }
}

void setCleaningMode(String mode) {
    Integer val = CLEANING_NAME_TO_MODE[mode]
    if (val == null) {
        logWarn "Invalid cleaning mode: ${mode}"
        return
    }
    logInfo "Setting cleaning mode to ${mode} (${val})"
    if (setProperty(4, 23, val) != null) {
        sendEvent(name: "cleaningMode", value: mode)
    }
}

void setWetnessLevel(BigDecimal level) {
    int val = Math.max(1, Math.min(32, level.toInteger()))
    logInfo "Setting wetness level to ${val}"
    if (setProperty(28, 1, val) != null) {
        sendEvent(name: "wetnessLevel", value: val)
    }
}

void startAutoEmpty() {
    logInfo "Triggering auto-empty"
    callAction(15, 1)
    runIn(3, "refresh")
}

void startWashing() {
    logInfo "Starting mop washing"
    callAction(4, 4, [[piid: 10, value: "2,1"]])
    runIn(3, "refresh")
}

void pauseWashing() {
    logInfo "Pausing mop washing"
    callAction(4, 4, [[piid: 10, value: "1,0"]])
    runIn(3, "refresh")
}

void startDrying() {
    logInfo "Starting mop drying"
    callAction(4, 4, [[piid: 10, value: "3,1"]])
    runIn(3, "refresh")
}

void stopDrying() {
    logInfo "Stopping mop drying"
    callAction(4, 4, [[piid: 10, value: "3,0"]])
    runIn(3, "refresh")
}

void setDnd(String enabled) {
    boolean val = (enabled == "true")
    logInfo "Setting Do Not Disturb to ${val}"
    if (setProperty(5, 1, val) != null) {
        sendEvent(name: "dndEnabled", value: val.toString())
    }
}

void setChildLock(String enabled) {
    boolean val = (enabled == "true")
    logInfo "Setting Child Lock to ${val}"
    if (setProperty(4, 27, val) != null) {
        sendEvent(name: "childLock", value: val.toString())
    }
}

void setVolume(BigDecimal level) {
    int val = Math.max(0, Math.min(100, level.toInteger()))
    logInfo "Setting volume to ${val}"
    if (setProperty(7, 1, val) != null) {
        sendEvent(name: "volume", value: val)
    }
}

void resetConsumable(String consumable) {
    Map act = RESET_CONSUMABLE_ACTIONS[consumable]
    if (!act) {
        logWarn "Unknown consumable: ${consumable}"
        return
    }
    logInfo "Resetting consumable: ${consumable}"
    callAction(act.siid as Integer, act.aiid as Integer)
    runIn(3, "refresh")
}

// ============================================================================
// Polling & Property Handling
// ============================================================================

void refresh() {
    String did = getDataValue("did")
    String bindDomain = getDataValue("bindDomain")
    if (!did) {
        logWarn "Cannot refresh: device DID not set"
        return
    }

    logDebug "Refreshing properties for DID ${did}"

    // Query properties in batches of 15 (matching Tasshack/dreame-vacuum max_properties)
    int batchSize = 15
    boolean anySuccess = false

    for (int i = 0; i < CORE_PROPERTIES.size(); i += batchSize) {
        int end = Math.min(i + batchSize, CORE_PROPERTIES.size())
        List<Map> batch = CORE_PROPERTIES.subList(i, end).collect { Map p ->
            [did: "${p.siid}.${p.piid}", siid: p.siid, piid: p.piid]
        }

        def result = parent.sendDeviceCommand(did, bindDomain, "get_properties", batch)
        if (result instanceof List) {
            anySuccess = true
            processPropertyList(result as List)
        } else {
            logDebug "Batch ${i}-${end} returned no results"
        }
    }

    if (anySuccess) {
        sendEvent(name: "online", value: "true")
        sendEvent(name: "lastUpdated", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
        updateConsumableSummary()
    } else {
        sendEvent(name: "online", value: "false")
    }
}

private void processPropertyList(List items) {
    items.each { item ->
        if (!(item instanceof Map)) return
        Integer code = (item.code != null) ? (item.code as Integer) : 0
        if (code != 0 || !item.containsKey("value")) return
        Integer siid = item.siid as Integer
        Integer piid = item.piid as Integer
        applyProperty(siid, piid, item.value)
    }
}

private void applyProperty(Integer siid, Integer piid, def val) {
    if (siid == null || piid == null || val == null) return
    String sp = "${siid}.${piid}"
    logDebug "Property ${sp} = ${val}"

    switch (sp) {
        case "2.1": // STATE
            String stateName = STATE_MAP[val as Integer] ?: "unknown_${val}"
            sendEvent(name: "state", value: stateName, descriptionText: "${device.displayName} state is ${stateName}")
            sendEvent(name: "switch", value: ACTIVE_STATES.contains(stateName) ? "on" : "off")
            break
        case "2.2": // ERROR
            Integer errCode = val as Integer
            String errDesc = ERROR_MAP[errCode] ?: "Error code ${errCode}"
            sendEvent(name: "errorCode", value: errCode)
            sendEvent(name: "errorDescription", value: errDesc)
            break
        case "3.1": // BATTERY_LEVEL
            sendEvent(name: "battery", value: val as Integer, unit: "%")
            break
        case "3.2": // CHARGING_STATUS
            String chg = CHARGING_STATUS_MAP[val as Integer] ?: "unknown_${val}"
            sendEvent(name: "chargingStatus", value: chg)
            break
        case "4.1": // STATUS
            String st = STATUS_MAP[val as Integer] ?: "unknown_${val}"
            sendEvent(name: "status", value: st)
            break
        case "4.2": // CLEANING_TIME
            sendEvent(name: "cleaningTime", value: val as Integer, unit: "min")
            break
        case "4.3": // CLEANED_AREA
            sendEvent(name: "cleanedArea", value: val as Integer, unit: "m²")
            break
        case "4.4": // SUCTION_LEVEL
            String sName = SUCTION_LEVEL_TO_NAME[val as Integer] ?: val.toString()
            sendEvent(name: "suctionLevel", value: sName)
            break
        case "4.5": // WATER_VOLUME
            String wName = WATER_VOLUME_TO_NAME[val as Integer] ?: val.toString()
            sendEvent(name: "waterVolume", value: wName)
            break
        case "4.7": // TASK_STATUS
            String ts = TASK_STATUS_MAP[val as Integer] ?: "unknown_${val}"
            sendEvent(name: "taskStatus", value: ts)
            break
        case "4.23": // CLEANING_MODE
            String cm = CLEANING_MODE_TO_NAME[val as Integer] ?: val.toString()
            sendEvent(name: "cleaningMode", value: cm)
            break
        case "4.25": // SELF_WASH_BASE_STATUS
            String sw = SELF_WASH_STATUS_MAP[val as Integer] ?: "unknown_${val}"
            sendEvent(name: "selfWashBaseStatus", value: sw)
            break
        case "4.27": // CHILD_LOCK
            sendEvent(name: "childLock", value: (val == true || val == 1).toString())
            break
        case "4.63": // CLEANING_PROGRESS
            sendEvent(name: "cleaningProgress", value: val as Integer, unit: "%")
            break
        case "5.1": // DND
            sendEvent(name: "dndEnabled", value: (val == true || val == 1).toString())
            break
        case "7.1": // VOLUME
            sendEvent(name: "volume", value: val as Integer, unit: "%")
            break
        case "9.1": // MAIN_BRUSH_TIME_LEFT
            sendEvent(name: "mainBrushTimeLeft", value: val as Integer, unit: "h")
            break
        case "9.2": // MAIN_BRUSH_LEFT
            sendEvent(name: "mainBrushLeft", value: val as Integer, unit: "%")
            break
        case "10.1": // SIDE_BRUSH_TIME_LEFT
            sendEvent(name: "sideBrushTimeLeft", value: val as Integer, unit: "h")
            break
        case "10.2": // SIDE_BRUSH_LEFT
            sendEvent(name: "sideBrushLeft", value: val as Integer, unit: "%")
            break
        case "11.1": // FILTER_LEFT
            sendEvent(name: "filterLeft", value: val as Integer, unit: "%")
            break
        case "11.2": // FILTER_TIME_LEFT
            sendEvent(name: "filterTimeLeft", value: val as Integer, unit: "h")
            break
        case "12.2": // TOTAL_CLEANING_TIME
            sendEvent(name: "totalCleaningTime", value: val as Integer, unit: "min")
            break
        case "12.3": // CLEANING_COUNT
            sendEvent(name: "cleaningCount", value: val as Integer)
            break
        case "12.4": // TOTAL_CLEANED_AREA
            sendEvent(name: "totalCleanedArea", value: val as Integer, unit: "m²")
            break
        case "15.5": // AUTO_EMPTY_STATUS
            String ae = AUTO_EMPTY_STATUS_MAP[val as Integer] ?: "unknown_${val}"
            sendEvent(name: "autoEmptyStatus", value: ae)
            break
        case "16.1": // SENSOR_DIRTY_LEFT
            sendEvent(name: "sensorDirtyLeft", value: val as Integer, unit: "%")
            break
        case "18.1": // MOP_PAD_LEFT
            sendEvent(name: "mopPadLeft", value: val as Integer, unit: "%")
            break
        case "19.2": // SILVER_ION_LEFT
            sendEvent(name: "silverIonLeft", value: val as Integer, unit: "%")
            break
        case "20.1": // DETERGENT_LEFT
            sendEvent(name: "detergentLeft", value: val as Integer, unit: "%")
            break
        case "27.1": // CLEAN_WATER_TANK_STATUS
            sendEvent(name: "cleanWaterTankStatus", value: val as Integer)
            break
        case "27.2": // DIRTY_WATER_TANK_STATUS
            sendEvent(name: "dirtyWaterTankStatus", value: val as Integer)
            break
        case "27.3": // DUST_BAG_STATUS
            sendEvent(name: "dustBagStatus", value: val as Integer)
            break
        case "27.4": // DETERGENT_STATUS
            sendEvent(name: "detergentStatus", value: val as Integer)
            break
        case "28.1": // WETNESS_LEVEL
            sendEvent(name: "wetnessLevel", value: val as Integer)
            break
    }
}

private void updateConsumableSummary() {
    int threshold = (consumableLowThreshold ?: 10) as Integer
    List<String> tracked = ["mainBrushLeft", "sideBrushLeft", "filterLeft", "sensorDirtyLeft", "mopPadLeft"]
    boolean needsReplace = false
    boolean needsMaintenance = false

    tracked.each { String attr ->
        def v = device.currentValue(attr)
        if (v != null) {
            int pct = v as Integer
            if (pct <= 0) {
                needsReplace = true
            } else if (pct <= threshold) {
                needsMaintenance = true
            }
        }
    }

    String statusVal = needsReplace ? "replace" : (needsMaintenance ? "maintenance_required" : "good")
    sendEvent(name: "consumableStatus", value: statusVal)
}

// ============================================================================
// MQTT Real-Time Push Support (Hubitat interfaces.mqtt)
// ============================================================================

void connectMqtt() {
    String did = getDataValue("did")
    if (!did) return

    try {
        if (interfaces.mqtt.isConnected()) {
            logDebug "MQTT already connected"
            return
        }
    } catch (Exception ignored) {
    }

    Map cfg = parent.getMqttConfig(did, getDataValue("bindDomain"), getDataValue("masterUid"), getDataValue("model"))
    if (!cfg || !cfg.brokerUrl || !cfg.username || !cfg.password) {
        logWarn "MQTT config unavailable; continuing with HTTP polling"
        sendEvent(name: "mqttStatus", value: "disconnected")
        return
    }

    state.mqttTopics = cfg.topics
    logInfo "Connecting to Dreamehome MQTT broker ${cfg.brokerUrl}"

    try {
        interfaces.mqtt.connect(
            cfg.brokerUrl,
            cfg.clientId,
            cfg.username,
            cfg.password,
            ignoreSSLIssues: true
        )
    } catch (Exception e) {
        logWarn "MQTT connect failed (falling back to HTTP polling): ${e.message}"
        sendEvent(name: "mqttStatus", value: "disconnected")
        scheduleMqttReconnect()
    }
}

void disconnectMqtt() {
    unschedule("connectMqtt")
    try {
        if (interfaces.mqtt.isConnected()) {
            interfaces.mqtt.disconnect()
        }
    } catch (Exception ignored) {
    }
    sendEvent(name: "mqttStatus", value: "disconnected")
}

void mqttClientStatus(String message) {
    logDebug "MQTT status: ${message}"
    if (message.startsWith("Status: Connection succeeded")) {
        state.mqttRetryDelay = 15
        sendEvent(name: "mqttStatus", value: "connected")
        sendEvent(name: "online", value: "true")
        (state.mqttTopics ?: []).each { String topic ->
            logDebug "Subscribing to MQTT topic: ${topic}"
            try {
                interfaces.mqtt.subscribe(topic)
            } catch (Exception e) {
                logWarn "Failed to subscribe to ${topic}: ${e.message}"
            }
        }
    } else if (message.startsWith("Error:")) {
        logWarn "MQTT error: ${message}"
        sendEvent(name: "mqttStatus", value: "disconnected")
        if (getDataValue("enableMqtt") == "true") {
            scheduleMqttReconnect()
        }
    }
}

private void scheduleMqttReconnect() {
    int delay = (state.mqttRetryDelay ?: 15) as Integer
    delay = Math.min(delay * 2, 300)
    state.mqttRetryDelay = delay
    logDebug "Scheduling MQTT reconnect in ${delay}s"
    runIn(delay, "connectMqtt")
}

void parse(String description) {
    try {
        Map msg = interfaces.mqtt.parseMessage(description)
        if (!msg?.payload) return
        logDebug "MQTT message on ${msg.topic}: ${msg.payload}"

        def json = new JsonSlurper().parseText(msg.payload)
        if (!(json instanceof Map) || !json.data) return

        Map data = json.data as Map
        if (data.method == "properties_changed" && data.params instanceof List) {
            processPropertyList(data.params as List)
            sendEvent(name: "online", value: "true")
            sendEvent(name: "lastUpdated", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
            updateConsumableSummary()
        }
    } catch (Exception e) {
        logDebug "Error parsing MQTT message: ${e.message}"
    }
}

// ============================================================================
// Parent Command Helpers
// ============================================================================

private def setProperty(Integer siid, Integer piid, def value) {
    String did = getDataValue("did")
    String bindDomain = getDataValue("bindDomain")
    if (!did) {
        logWarn "Cannot setProperty: device DID not set"
        return null
    }
    List<Map> params = [[did: did, siid: siid, piid: piid, value: value]]
    return parent.sendDeviceCommand(did, bindDomain, "set_properties", params)
}

private def callAction(Integer siid, Integer aiid, List<Map> inParams = []) {
    String did = getDataValue("did")
    String bindDomain = getDataValue("bindDomain")
    if (!did) {
        logWarn "Cannot callAction: device DID not set"
        return null
    }
    Map params = [
        did : did,
        siid: siid,
        aiid: aiid,
        "in": inParams ?: []
    ]
    return parent.sendDeviceCommand(did, bindDomain, "action", params)
}

private int getCurrentSuctionInt() {
    String cur = device.currentValue("suctionLevel")
    return SUCTION_NAME_TO_LEVEL[cur] != null ? SUCTION_NAME_TO_LEVEL[cur] : 1
}

private int getCurrentWaterInt() {
    String cur = device.currentValue("waterVolume")
    return WATER_NAME_TO_VOLUME[cur] != null ? WATER_NAME_TO_VOLUME[cur] : 2
}

// ============================================================================
// Logging
// ============================================================================

private void logDebug(String msg) {
    if (debugLogging) log.debug "[${device.displayName}] ${msg}"
}

private void logInfo(String msg) {
    if (infoLogging != false) log.info "[${device.displayName}] ${msg}"
}

private void logWarn(String msg) {
    log.warn "[${device.displayName}] ${msg}"
}
