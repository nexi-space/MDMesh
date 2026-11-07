// Builds the Android QR provisioning payload for the MDMesh agent. Crucially it carries the
// SERVER_URL extra, so one prebuilt APK works for any deployment (the agent reads it at enrollment).
//
// Deploy-specific values default to the shipped debug build and can be overridden at web-build time:
//   VITE_AGENT_PACKAGE     applicationId of the distributed APK (default com.mdmesh.agent.debug)
//   VITE_AGENT_CHECKSUM    PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM of the signing cert
//   VITE_AGENT_APK_URL     where the APK is hosted (default <origin>/files/agent.apk)
//   VITE_AGENT_ADMIN_EXTRAS     a JSON object merged into the agent's admin extras bundle (a deployment's own agent
//                               settings, e.g. com.mdmesh.HIDE_PACKAGES)
//   VITE_PROVISIONING_EXTRAS    a JSON object merged into the provisioning payload itself (e.g.
//                               android.app.extra.PROVISIONING_SKIP_EDUCATION_SCREENS)
// The console's own keys always win over both: they cannot change the token, server, agent or checksum.

const env = import.meta.env as Record<string, string | undefined>;

/** A JSON object from a build-time setting, or {} when it is unset or not an object. */
function jsonObject(raw: string | undefined): Record<string, unknown> {
  if (!raw) return {};
  try {
    const v: unknown = JSON.parse(raw);
    return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}

const EXTRA_ADMIN_EXTRAS = jsonObject(env.VITE_AGENT_ADMIN_EXTRAS);
const EXTRA_PROVISIONING = jsonObject(env.VITE_PROVISIONING_EXTRAS);

const AGENT_PACKAGE = env.VITE_AGENT_PACKAGE || 'com.mdmesh.agent.debug';
const ADMIN_COMPONENT = `${AGENT_PACKAGE}/com.mdmesh.agent.admin.AdminReceiver`;
const SIGNATURE_CHECKSUM = env.VITE_AGENT_CHECKSUM || 'YMEWhg_-ydciRFgtdOZbQ-W_aPPz7orHKuqfn13Ujaw';

/** Server origin — same-origin in production (Caddy serves the SPA and proxies the API). */
export function serverBaseUrl(): string {
  return window.location.origin;
}

export function agentApkUrl(): string {
  // VITE_AGENT_APK_URL may be absolute (https://…) or origin-relative (/update/agent.apk → resolved
  // against this deployment's origin). Release builds set it to the supervisor's verified APK mirror.
  const u = env.VITE_AGENT_APK_URL;
  if (u) return u.startsWith('/') ? `${serverBaseUrl()}${u}` : u;
  return `${serverBaseUrl()}/files/agent.apk`;
}

export type WifiSecurity = 'WPA' | 'WEP' | 'NONE' | 'EAP';
/** Optional Wi-Fi to bake into the QR so the device joins it DURING provisioning (before it downloads
 *  the agent). The password is embedded in the QR in plaintext — that's inherent to Android QR Wi-Fi. */
export interface WifiConfig {
  ssid: string;
  password?: string;
  security?: WifiSecurity;
}

/** The provisioning JSON a fresh device scans (6-tap → QR scanner). Pass `wifi` to pre-connect it. */
export function buildProvisioningPayload(token: string, wifi?: WifiConfig): string {
  const payload: Record<string, unknown> = {
    ...EXTRA_PROVISIONING,
    'android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME': ADMIN_COMPONENT,
    'android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM': SIGNATURE_CHECKSUM,
    'android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION': agentApkUrl(),
    'android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE': {
      ...EXTRA_ADMIN_EXTRAS,
      'com.mdmesh.ENROLL_TOKEN': token,
      'com.mdmesh.SERVER_URL': serverBaseUrl(),
    },
    'android.app.extra.PROVISIONING_SKIP_ENCRYPTION': true,
    'android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED': true,
  };
  const ssid = wifi?.ssid?.trim();
  if (ssid) {
    const security: WifiSecurity = wifi?.security ?? (wifi?.password ? 'WPA' : 'NONE');
    payload['android.app.extra.PROVISIONING_WIFI_SSID'] = ssid;
    payload['android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE'] = security;
    if (security !== 'NONE' && wifi?.password) {
      payload['android.app.extra.PROVISIONING_WIFI_PASSWORD'] = wifi.password;
    }
  }
  return JSON.stringify(payload);
}
