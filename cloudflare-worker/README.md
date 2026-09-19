# Secure embedding gateway

This Worker is an optional server-side gateway for GapGPT embeddings. It keeps
`GAPGPT_API_KEY` outside the Android APK and Git history.

It deliberately requires a short-lived HS256 JWT. Before deployment, connect
the application to a real authentication/entitlement service that issues those
tokens. Do not replace the JWT check with a static token in the mobile app.

Only send user-approved text metadata. Local `.lrc` content is private by
default and must not be sent to the endpoint unless the user explicitly agrees.

Deployment requires the owner to configure Cloudflare authentication, rate
limits, billing/entitlements, and secrets. Payment/subscription collection is
not implemented here because it requires an approved payment provider and
merchant configuration.
