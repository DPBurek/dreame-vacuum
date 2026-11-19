/**
 *  Dreamehome Vacuum Manager
 *
 *  Hubitat Elevation Parent App for Dreame / MOVA / Trouver robot vacuums
 *  via the Dreamehome Cloud API (HTTPS + MQTT).
 *
 *  Ported from Tasshack/dreame-vacuum (Python / Home Assistant):
 *  https://github.com/Tasshack/dreame-vacuum
 *
 *  Licensed under the MIT License.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@Field static final String APP_VERSION = "1.0.0"
@Field static final String CHILD_NAMESPACE = "dpburek"
@Field static final String CHILD_DRIVER_NAME = "Dreamehome Vacuum"

@Field static final Map ACCOUNT_PROFILES = [
    dreame: [
        domain  : ".iot.dreame.tech",
        port    : "13267",
        tenantId: "000000",
        cid     : "EETjszu*XI5znHsI",
        vid     : "102060300",
        au      : "ZHJlYW1lX2FwcHYxOkFQXmR2QHpAU1FZVnhOODg=",
        ua      : "Dart/3.9 (dart:io)",
        mt      : true,
        psd     : null
    ],
    mova: [
        domain  : ".iot.mova-tech.com",
        port    : "13267",
        tenantId: "000002",
        cid     : "gigxlmqwZ]7oWZUF",
        vid     : "10605530",
        au      : "bW92YV9hcHA6VjdLb0NoTFc4dkhBQ3FHYg==",
        ua      : "Dart/3.10 (dart:io)",
        mt      : true,
        psd     : "new"
    ],
    trouver: [
        domain  : ".iot.trouver-tech.com",
        port    : "13267",
        tenantId: "000005",
        cid     : "MMd02jyRqzuf1QBS",
        vid     : "10300532",
        au      : "dHJvdXZlcl9hcHA6NXB0MGV6d0w1TzRHMG96aw==",
        ua      : "Dart/3.6 (dart:io)",
        mt      : false,
        psd     : null
    ]
]

@Field static final String PASSWORD_SALT = "RAylYC%fmSKp7%Tq"

@Field static final Map COUNTRY_OPTIONS = [
    us: "United States (us)",
    eu: "Europe (eu)",
    cn: "China (cn)",
    sg: "Singapore (sg)",
    ru: "Russia (ru)",
    kr: "South Korea (kr)",
    in: "India (in)",
    de: "Germany (de)"
]

@Field static final Map POLL_INTERVAL_OPTIONS = [
    "0" : "Manual only (no polling)",
    "1" : "Every 1 minute",
    "5" : "Every 5 minutes (Recommended)",
    "10": "Every 10 minutes",
    "15": "Every 15 minutes",
    "30": "Every 30 minutes"
]

definition(
    name: "Dreamehome Vacuum Manager",
    namespace: "dpburek",
    author: "DPBurek (ported from Tasshack/dreame-vacuum)",
    description: "Connect and control Dreame, MOVA, and Trouver robot vacuums via Dreamehome Cloud (HTTPS + MQTT).",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    singleInstance: true
)

preferences {
    page(name: "mainPage")
    page(name: "discoverPage")
}

// ============================================================================
// UI Pages
// ============================================================================

def mainPage() {
    dynamicPage(name: "mainPage", title: "Dreamehome Vacuum Manager v${APP_VERSION}", install: true, uninstall: true) {
        section("<b>Cloud Account Credentials</b>") {
            input name: "accountType", type: "enum", title: "App Ecosystem", options: [
                dreame : "Dreamehome",
                mova   : "MovaHome",
                trouver: "Trouver"
            ], defaultValue: "dreame", required: true
            input name: "country", type: "enum", title: "Server Region / Country", options: COUNTRY_OPTIONS, defaultValue: "us", required: true
            input name: "username", type: "text", title: "Account Email or Phone", required: true
            input name: "password", type: "password", title: "Account Password", required: true
        }

        section("<b>Authentication & Device Discovery</b>") {
            String authStatus = state.accessToken ? "Authenticated (UID: ${state.uid ?: 'unknown'})" : "Not authenticated yet"
            if (state.lastAuthError) {
                authStatus = "Last error: ${state.lastAuthError}"
            }
            paragraph "Status: <b>${authStatus}</b>"
            href name: "toDiscoverPage", page: "discoverPage", title: "Authenticate & Discover Vacuums", description: "Sign in to Dreamehome Cloud and fetch your vacuum list"
        }

        if (state.discoveredDevices && state.discoveredDevices.size() > 0) {
            Map deviceOptions = [:]
            state.discoveredDevices.each { String did, Map info ->
                deviceOptions[did] = "${info.name} (${info.model}) [${did}]"
            }
            section("<b>Select Vacuums to Manage</b>") {
                input name: "selectedDevices", type: "enum", title: "Vacuums", options: deviceOptions, multiple: true, required: false
            }
        }

        section("<b>Sync & Logging Options</b>") {
            input name: "pollInterval", type: "enum", title: "HTTP Polling Interval", options: POLL_INTERVAL_OPTIONS, defaultValue: "5", required: true
            input name: "enableMqtt", type: "bool", title: "Enable Real-time MQTT Push Updates", defaultValue: true, description: "Connects each vacuum driver to Dreamehome MQTT for instant status updates (falls back to HTTP polling if unavailable)."
            input name: "debugLogging", type: "bool", title: "Enable Debug Logging", defaultValue: false
        }
    }
}

def discoverPage() {
    dynamicPage(name: "discoverPage", title: "Authenticating & Discovering Vacuums", nextPage: "mainPage") {
        section {
            if (!username || !password) {
                paragraph "Please enter your account username and password on the previous page first."
                return
            }

            boolean ok = login(true)
            if (!ok) {
                paragraph "<b>Login failed:</b> ${state.lastAuthError ?: 'Unknown error'}. Check your credentials, region, and debug logs."
                return
            }

            Map devices = discoverDevices()
            if (devices == null) {
                paragraph "<b>Logged in successfully</b>, but device discovery failed. Check Hubitat logs for details."
            } else if (devices.isEmpty()) {
                paragraph "<b>Logged in successfully</b>, but no Dreame vacuums were found on this account/region (${country})."
            } else {
                StringBuilder sb = new StringBuilder("<b>Found ${devices.size()} device(s):</b><ul>")
                devices.each { String did, Map info ->
                    sb.append("<li><b>${info.name}</b> — ${info.model} (DID: ${did})</li>")
                }
                sb.append("</ul>Click <b>Next</b> to select which vacuums to add to Hubitat.")
                paragraph sb.toString()
            }
        }
    }
}

// ============================================================================
// Lifecycle
// ============================================================================

def installed() {
    logInfo "Installed Dreamehome Vacuum Manager v${APP_VERSION}"
    initialize()
}

def updated() {
    logInfo "Updated Dreamehome Vacuum Manager v${APP_VERSION}"
    unschedule()
    initialize()
}

def uninstalled() {
    logInfo "Uninstalling Dreamehome Vacuum Manager and removing child devices"
    getChildDevices().each { child ->
        deleteChildDevice(child.deviceNetworkId)
    }
}

def initialize() {
    ensureVisitorId()
    syncChildDevices()
    schedulePolling()
}

private void syncChildDevices() {
    List<String> selected = (selectedDevices ?: []) as List<String>
    Map<String, Map> discovered = (state.discoveredDevices ?: [:]) as Map<String, Map>

    // Create or update selected child devices
    selected.each { String did ->
        String dni = "dreame-${did}"
        Map devInfo = discovered[did]
        if (!devInfo) {
            logWarn "Selected device ${did} not found in discovered devices"
            return
        }

        // Fetch full device info (bindDomain, masterUid, model)
        Map fullInfo = getDeviceInfo(did)
        if (fullInfo) {
            devInfo.bindDomain = fullInfo.bindDomain ?: devInfo.bindDomain
            devInfo.masterUid = fullInfo.masterUid ?: devInfo.masterUid
            devInfo.model = fullInfo.model ?: devInfo.model
            discovered[did] = devInfo
        }

        def child = getChildDevice(dni)
        if (!child) {
            logInfo "Creating child device '${devInfo.name}' (${dni})"
            try {
                child = addChildDevice(CHILD_NAMESPACE, CHILD_DRIVER_NAME, dni, [
                    name : devInfo.name,
                    label: devInfo.name
                ])
            } catch (Exception e) {
                logError "Failed to create child device ${dni} (ensure '${CHILD_DRIVER_NAME}' driver is installed): ${e.message}"
                return
            }
        }

        child.updateDeviceMetadata([
            did       : did,
            model     : devInfo.model,
            mac       : devInfo.mac,
            masterUid : devInfo.masterUid,
            bindDomain: devInfo.bindDomain,
            country   : country ?: "us",
            enableMqtt: (enableMqtt != false)
        ])
    }

    state.discoveredDevices = discovered

    // Remove unselected child devices
    getChildDevices().each { child ->
        String did = child.deviceNetworkId.replaceFirst(/^dreame-/, "")
        if (!selected.contains(did)) {
            logInfo "Removing unselected child device ${child.displayName} (${child.deviceNetworkId})"
            deleteChildDevice(child.deviceNetworkId)
        }
    }
}

private void schedulePolling() {
    unschedule("pollAllDevices")
    int mins = (pollInterval ?: "5").toInteger()
    if (mins <= 0) {
        logDebug "Scheduled polling disabled (manual only)"
        return
    }
    switch (mins) {
        case 1:
            runEvery1Minute("pollAllDevices")
            break
        case 5:
            runEvery5Minutes("pollAllDevices")
            break
        case 10:
            runEvery10Minutes("pollAllDevices")
            break
        case 15:
            runEvery15Minutes("pollAllDevices")
            break
        case 30:
            runEvery30Minutes("pollAllDevices")
            break
        default:
            runEvery5Minutes("pollAllDevices")
    }
    logDebug "Scheduled polling every ${mins} minute(s)"
}

def pollAllDevices() {
    logDebug "Polling all managed Dreame vacuums"
    getChildDevices().each { child ->
        try {
            child.refresh()
        } catch (Exception e) {
            logWarn "Error polling ${child.displayName}: ${e.message}"
        }
    }
}

// ============================================================================
// Dreamehome Cloud Protocol (Ported from protocol.py)
// ============================================================================

private Map getProfile() {
    return ACCOUNT_PROFILES[accountType ?: "dreame"] ?: ACCOUNT_PROFILES.dreame
}

private String getApiUrl() {
    Map p = getProfile()
    String c = (country ?: "us").toLowerCase()
    return "https://${c}${p.domain}:${p.port}"
}

private String ensureVisitorId() {
    if (!state.visitorId) {
        String seed = "${location?.hub?.id ?: 'hubitat'}-${now()}-${Math.random()}"
        state.visitorId = md5Hex(seed)
    }
    return state.visitorId
}

Boolean ensureAuthenticated() {
    long nowSec = (long) (now() / 1000L)
    if (state.accessToken && state.tokenExpire && (state.tokenExpire - nowSec) > 600L) {
        return true
    }
    return login(false)
}

Boolean login(Boolean forcePassword = false) {
    if (!username || !password) {
        state.lastAuthError = "Missing username or password"
        return false
    }

    ensureVisitorId()
    boolean usedRefresh = (!forcePassword && state.refreshToken)
    String bodyStr

    if (usedRefresh) {
        bodyStr = "grant_type=refresh_token&scope=all&platform=ANDROID&type=account&refresh_token=${URLEncoder.encode(state.refreshToken.toString(), 'UTF-8')}"
    } else {
        String pwHash = md5Hex("${password}${PASSWORD_SALT}")
        bodyStr = "grant_type=password&scope=all&platform=ANDROID&type=account&username=${URLEncoder.encode(username.toString(), 'UTF-8')}&password=${pwHash}"
        if (state.ccode && state.lang) {
            bodyStr += "&country=${URLEncoder.encode(state.ccode.toString(), 'UTF-8')}&lang=${URLEncoder.encode(state.lang.toString(), 'UTF-8')}"
        }
    }

    Map headers = buildBaseHeaders("application/x-www-form-urlencoded")
    String url = "${getApiUrl()}/dreame-auth/oauth/token"
    logDebug "Logging in to ${url} (refresh=${usedRefresh})"

    try {
        Map respData = null
        httpPost([
            uri               : url,
            headers           : headers,
            requestContentType: "application/x-www-form-urlencoded",
            contentType       : "application/json",
            body              : bodyStr,
            timeout           : 15
        ]) { resp ->
            respData = parseResponseData(resp?.data)
        }

        if (respData && respData.access_token) {
            state.accessToken = respData.access_token
            state.refreshToken = respData.refresh_token ?: state.refreshToken
            long expiresIn = (respData.expires_in ?: 7200) as Long
            state.tokenExpire = ((long) (now() / 1000L)) + expiresIn
            state.uid = respData.uid?.toString() ?: state.uid
            state.domain = respData.domain ?: state.domain
            state.region = respData.region ?: state.region
            state.lang = respData.lang ?: state.lang
            state.ccode = respData.country ?: state.ccode
            state.tenantId = respData.tenant_id ?: state.tenantId
            state.lastAuthError = null
            logDebug "Dreamehome login succeeded (expires in ${expiresIn}s)"
            return true
        }

        state.lastAuthError = "Unexpected login response: ${respData}"
        logWarn state.lastAuthError
        return false
    } catch (groovyx.net.http.HttpResponseException e) {
        int status = e.statusCode
        Map errData = parseResponseData(e.response?.data)
        String errDesc = errData?.error_description?.toString() ?: errData?.toString() ?: e.message
        if (usedRefresh && status in [400, 401, 403] && errDesc?.toLowerCase()?.contains("refresh token")) {
            logWarn "Refresh token expired, falling back to password login"
            state.refreshToken = null
            return login(true)
        }
        state.lastAuthError = "HTTP ${status}: ${errDesc}"
        logError "Dreamehome login failed (${status}): ${errDesc}"
        return false
    } catch (Exception e) {
        state.lastAuthError = e.message
        logError "Dreamehome login exception: ${e.message}"
        return false
    }
}

private Map buildBaseHeaders(String contentType) {
    Map p = getProfile()
    String meta = "cv=a_${p.vid}"
    if (p.mt) {
        String cHash = md5Hex("${username}c").substring(0, 8)
        String wHash = md5Hex("${username}w").substring(0, 8)
        meta += ";canvasHash=${cHash};webglHash=${wHash};visitorIdHash=${ensureVisitorId()}"
    }

    Map headers = [
        "User-Agent"   : p.ua,
        "dreame-meta"  : meta,
        "tenant-id"    : state.tenantId ?: p.tenantId,
        "authorization": "Basic ${p.au}",
        "content-type" : contentType
    ]

    if (state.region && state.lang && state.ccode) {
        String rlcPlain = "${state.region}|${state.lang}|${state.ccode}"
        String rlcEnc = aesEcbEncryptBase64(rlcPlain, p.cid)
        if (rlcEnc) {
            headers["dreame-rlc"] = rlcEnc
        }
    }

    if (p.psd) {
        headers["dreame-psd"] = p.psd
    }

    return headers
}

private Map buildAuthHeaders(String contentType = "application/json") {
    Map headers = buildBaseHeaders(contentType)
    if (state.accessToken) {
        headers["dreame-auth"] = state.accessToken
    }
    return headers
}

Map apiCall(String path, Map params = null, Boolean retried = false) {
    if (!ensureAuthenticated()) {
        logWarn "API call ${path} aborted: not authenticated"
        return null
    }

    String url = "${getApiUrl()}/${path}"
    Map signedBody = (params != null) ? signParams(params) : null
    String jsonBody = (signedBody != null) ? toCompactJson(signedBody) : ""
    Map headers = buildAuthHeaders("application/json")

    logDebug "POST ${url} body=${jsonBody}"

    try {
        Map respData = null
        httpPost([
            uri               : url,
            headers           : headers,
            requestContentType: "application/json",
            contentType       : "application/json",
            body              : jsonBody,
            timeout           : 15
        ]) { resp ->
            respData = parseResponseData(resp?.data)
        }
        return respData
    } catch (groovyx.net.http.HttpResponseException e) {
        int status = e.statusCode
        Map errData = parseResponseData(e.response?.data)
        if (status == 401 && !retried) {
            logWarn "API call ${path} returned 401, re-authenticating and retrying"
            if (login(false)) {
                return apiCall(path, params, true)
            }
        }
        logWarn "API call ${path} failed (HTTP ${status}): ${errData ?: e.message}"
        return null
    } catch (Exception e) {
        logWarn "API call ${path} exception: ${e.message}"
        return null
    }
}

Map discoverDevices() {
    Map resp = apiCall("dreame-user-iot/iotuserbind/device/listV2")
    if (!resp || resp.code != 0 || !resp.data?.page?.records) {
        logWarn "Device discovery returned unexpected payload: ${resp}"
        return null
    }

    Map discovered = [:]
    resp.data.page.records.each { Map dev ->
        String did = dev.did?.toString()
        String model = dev.model?.toString() ?: "unknown"
        if (!did) return
        String name = dev.customName ?: dev.deviceInfo?.displayName ?: model
        discovered[did] = [
            did       : did,
            model     : model,
            name      : name,
            mac       : dev.mac?.toString() ?: "",
            masterUid : dev.masterUid?.toString() ?: "",
            bindDomain: dev.bindDomain?.toString() ?: ""
        ]
    }
    state.discoveredDevices = discovered
    logInfo "Discovered ${discovered.size()} device(s) on Dreamehome Cloud"
    return discovered
}

Map getDeviceInfo(String did) {
    Map resp = apiCall("dreame-user-iot/iotuserbind/device/info", [did: did])
    if (!resp || resp.code != 0 || !resp.data) {
        logWarn "getDeviceInfo(${did}) failed: ${resp}"
        return null
    }
    Map data = resp.data as Map

    Map otcResp = apiCall("dreame-user-iot/iotstatus/devOTCInfo", [did: did])
    if (otcResp && otcResp.code == 0 && otcResp.data?.otcInfo?.params) {
        Map merged = [:]
        merged.putAll(otcResp.data.otcInfo.params as Map)
        merged.putAll(data)
        return merged
    }
    return data
}

// ============================================================================
// Child Driver Bridge Methods
// ============================================================================

def sendDeviceCommand(String did, String bindDomain, String method, def parameters) {
    String hostPrefix = ""
    if (bindDomain && bindDomain.contains(".")) {
        hostPrefix = "-${bindDomain.split('\\.')[0]}"
    }

    int reqId = ((state.reqId ?: 100) as Integer) + 1
    if (reqId > 999999) reqId = 100
    state.reqId = reqId

    Map payload = [
        did : did.toString(),
        id  : reqId,
        data: [
            did   : did.toString(),
            id    : reqId,
            method: method,
            params: parameters
        ]
    ]

    String path = "dreame-iot-com${hostPrefix}/device/sendCommand"
    Map resp = apiCall(path, payload)
    if (!resp || resp.data == null || resp.data.result == null) {
        if (resp?.success == true) {
            // Mirror upstream retry-once when success=true but data is empty
            resp = apiCall(path, payload)
        }
    }
    if (!resp || resp.data == null || resp.data.result == null) {
        logWarn "Command ${method} failed for DID ${did}: ${resp}"
        return null
    }
    return resp.data.result
}

def getCachedProperties(String did, List keys) {
    Map resp = apiCall("dreame-user-iot/iotstatus/props", [
        did : did.toString(),
        keys: keys
    ])
    return resp?.data
}

Map getMqttConfig(String did, String bindDomain, String masterUid, String model) {
    if (!ensureAuthenticated()) {
        return null
    }
    if (!bindDomain || !masterUid || !model) {
        Map info = getDeviceInfo(did)
        if (info) {
            bindDomain = bindDomain ?: info.bindDomain?.toString()
            masterUid = masterUid ?: info.masterUid?.toString()
            model = model ?: info.model?.toString()
        }
    }
    if (!bindDomain || !bindDomain.contains(":")) {
        logWarn "Cannot build MQTT config for ${did}: missing bindDomain"
        return null
    }

    String[] hostParts = bindDomain.split(":")
    String brokerHost = hostParts[0]
    String brokerPort = hostParts[1]
    String c = (country ?: "us").toLowerCase()
    if (c == "kr") {
        brokerHost = brokerHost.replace("10100", "10000")
    }

    String clientId = "p_${md5Hex(did + 'mqtt' + ensureVisitorId())}"
    List<String> topics = ["/status/${did}/${masterUid}/${model}/${c}/"]
    if (c == "kr") {
        topics << "/status/${did}/${masterUid}/${model}/sg/"
    }

    return [
        brokerUrl: "ssl://${brokerHost}:${brokerPort}",
        clientId : clientId,
        username : state.uid?.toString(),
        password : state.accessToken?.toString(),
        topics   : topics
    ]
}

// ============================================================================
// Request Signing & Crypto Helpers (Ported from protocol.py)
// ============================================================================

private Map signParams(Map params) {
    long ms = now()
    String cid = getProfile().cid
    String base = spliced(params, true) + ms.toString() + cid
    Map result = new LinkedHashMap(params)
    result["sign"] = md5Hex(base)
    result["timestamp"] = ms
    return result
}

private String spliced(Map obj, Boolean top) {
    List<String> parts = []
    List<String> keys = obj.keySet().collect { it.toString() }.sort()
    keys.each { String k ->
        def v = obj[k]
        if (v instanceof Map) {
            String inner = spliced(v as Map, false)
            parts << (inner ? "${k}=[${inner}]" : "${k}=]")
        } else if (v instanceof List) {
            if (top) {
                parts << "${k}=${toSortedJson(v)}"
            }
        } else if (v instanceof Boolean) {
            parts << "${k}=${v ? 'true' : 'false'}"
        } else if (v == null) {
            parts << "${k}=null"
        } else if (top) {
            parts << "${k}=${v}"
        } else {
            parts << "${k}=${toSortedJson(v)}"
        }
    }
    return parts.join("&")
}

private String toSortedJson(def obj) {
    if (obj == null) return "null"
    if (obj instanceof Map) {
        Map sorted = new TreeMap()
        (obj as Map).each { k, v -> sorted[k.toString()] = v }
        StringBuilder sb = new StringBuilder("{")
        boolean first = true
        sorted.each { k, v ->
            if (!first) sb.append(",")
            first = false
            sb.append(JsonOutput.toJson(k.toString())).append(":").append(toSortedJson(v))
        }
        sb.append("}")
        return sb.toString()
    }
    if (obj instanceof List) {
        StringBuilder sb = new StringBuilder("[")
        boolean first = true
        (obj as List).each { item ->
            if (!first) sb.append(",")
            first = false
            sb.append(toSortedJson(item))
        }
        sb.append("]")
        return sb.toString()
    }
    return JsonOutput.toJson(obj)
}

private String toCompactJson(def obj) {
    return JsonOutput.toJson(obj)
}

private String md5Hex(String input) {
    MessageDigest md = MessageDigest.getInstance("MD5")
    byte[] digest = md.digest(input.getBytes("UTF-8"))
    return digest.collect { String.format("%02x", it & 0xff) }.join("")
}

private String aesEcbEncryptBase64(String plainText, String keyStr) {
    try {
        SecretKeySpec keySpec = new SecretKeySpec(keyStr.getBytes("UTF-8"), "AES")
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        byte[] encrypted = cipher.doFinal(plainText.getBytes("UTF-8"))
        return encrypted.encodeBase64().toString()
    } catch (Exception e) {
        logWarn "AES-ECB encryption failed: ${e.message}"
        return null
    }
}

private Map parseResponseData(def data) {
    if (data == null) return null
    if (data instanceof Map) return data as Map
    try {
        String text = (data instanceof InputStream) ? data.getText("UTF-8") : data.toString()
        def parsed = new JsonSlurper().parseText(text)
        return (parsed instanceof Map) ? (parsed as Map) : [raw: text]
    } catch (Exception e) {
        return [raw: data.toString()]
    }
}

// ============================================================================
// Logging
// ============================================================================

private void logDebug(String msg) {
    if (debugLogging) log.debug "[DreameManager] ${msg}"
}

private void logInfo(String msg) {
    log.info "[DreameManager] ${msg}"
}

private void logWarn(String msg) {
    log.warn "[DreameManager] ${msg}"
}

private void logError(String msg) {
    log.error "[DreameManager] ${msg}"
}
