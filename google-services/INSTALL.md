# Google Services — Installation

**Skills:** `email` (Gmail), `calendar`, `google_contacts`, `drive`  
**GitHub:** <https://github.com/rleibman/jorlan>

---

## 1. Create a Google Cloud Project

1. Go to <https://console.cloud.google.com/>
2. Create a new project (or select an existing one)
3. Enable the following APIs under **APIs & Services → Library**:
    - Gmail API
    - Google Calendar API
    - Google People API (for Contacts)
    - Google Drive API

---

## 2. Configure the OAuth Consent Screen

1. **APIs & Services → OAuth consent screen**
2. User type: **External** (for personal use) or **Internal** (G Suite / Workspace org)
3. App name: `Jorlan` (or your own name)
4. Add the following scopes:
    - `https://www.googleapis.com/auth/gmail.modify`
    - `https://www.googleapis.com/auth/calendar`
    - `https://www.googleapis.com/auth/contacts.readonly`
    - `https://www.googleapis.com/auth/drive`
5. Add test users (your own Google account) if using External type

---

## 3. Create OAuth Credentials

1. **APIs & Services → Credentials → Create Credentials → OAuth client ID**
2. Application type: **Web application**
3. Authorised redirect URI: `https://<your-jorlan-host>/oauth/google/callback`
    - For local development: `http://localhost:8080/oauth/google/callback`
4. Download the JSON credentials file

---

## 4. Configure Jorlan

Add the OAuth client details to `/etc/jorlan/server.env` (or the server configuration):

```env
GOOGLE_CLIENT_ID=your-client-id.apps.googleusercontent.com
GOOGLE_CLIENT_SECRET=your-client-secret
GOOGLE_REDIRECT_URI=https://your-jorlan-host/oauth/google/callback
```

Or set via `server_settings`:

```sql
INSERT INTO server_settings (`key`, `value`)
VALUES ('oauth.google.clientId', '"your-client-id"'),
       ('oauth.google.clientSecret', '"your-client-secret"'),
       ('oauth.google.redirectUri', '"https://your-jorlan-host/oauth/google/callback"')
ON DUPLICATE KEY UPDATE `value` = VALUES(`value`);
```

---

## 5. Connect user accounts

Each Jorlan user authorises their own Google account:

1. Navigate to **Settings → Integrations → Google**
2. Click **Connect Google Account**
3. Complete the Google OAuth flow

Tokens are stored encrypted per user.

---

## 6. Grant capabilities

| Capability                                | Skills   |
|-------------------------------------------|----------|
| `email.read`, `email.send`, `email.write` | Gmail    |
| `calendar.read`, `calendar.write`         | Calendar |
| `google_contacts.read`                    | Contacts |
| `drive.read`, `drive.write`               | Drive    |

Grant via **Admin → Agents → \<agent\> → Capabilities**.

---

## Troubleshooting

| Symptom                            | Check                                                                     |
|------------------------------------|---------------------------------------------------------------------------|
| "Redirect URI mismatch"            | The redirect URI in Google Cloud must exactly match `GOOGLE_REDIRECT_URI` |
| "Access blocked: app not verified" | Add yourself as a test user in the OAuth consent screen                   |
| Token refresh failures             | The user must re-authorise via Settings → Integrations                    |
| Scope errors                       | Ensure all 4 APIs are enabled in the Google Cloud project                 |
