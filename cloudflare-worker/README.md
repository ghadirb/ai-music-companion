# Secure embedding gateway

This Worker is an optional server-side gateway for GapGPT embeddings. It keeps
`GAPGPT_API_KEY` outside the Android APK and Git history.

It deliberately requires a short-lived HS256 JWT. Before deployment, connect
the application to a real authentication/entitlement service that issues those
tokens. Do not replace the JWT check with a static token in the mobile app.

Only send user-approved text metadata. Local `.lrc` content is private by
default and must not be sent to the endpoint unless the user explicitly agrees.

## Myket premium purchase

The Worker now contains the server boundary for Myket's **non-consumable**
`premium_lifetime` product. It issues a signed, ten-minute developer payload,
verifies the returned token directly with Myket, checks that the payload is
bound to the authenticated user, and records the durable entitlement in KV.
The Android app never receives `MYKET_ACCESS_TOKEN`.

Before deployment, configure all of the following yourself:

- A real sign-in service that issues the short-lived JWT used by this Worker.
- Cloudflare rate limiting and a KV binding called `PURCHASE_ENTITLEMENTS`.
- `MYKET_ACCESS_TOKEN`, `JWT_SIGNING_SECRET`, `GAPGPT_API_KEY` as secrets and
  `MYKET_PACKAGE_NAME=com.ghadirb.aimusic` as an environment value.
- The matching non-consumable SKU in the Myket developer panel and its public
  RSA key as the local Gradle property `MYKET_IAB_PUBLIC_KEY`.

Do not deploy the Worker until all of these are configured. Myket's current
documentation says recurring subscriptions are not supported; a future
recurring plan needs a provider/store that supports subscriptions.
