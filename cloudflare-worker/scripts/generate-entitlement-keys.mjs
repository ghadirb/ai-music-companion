// Generates the ES256 key pair used for signed entitlement tokens.
//   node scripts/generate-entitlement-keys.mjs
// - PRIVATE JWK  -> `wrangler secret put ENTITLEMENT_SIGNING_JWK` (server only, never commit it)
// - PUBLIC key   -> Android Gradle property ENTITLEMENT_PUBLIC_KEY (safe to ship in the APK)
import { webcrypto as crypto } from "node:crypto";

const pair = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
const privateJwk = await crypto.subtle.exportKey("jwk", pair.privateKey);
const spki = Buffer.from(await crypto.subtle.exportKey("spki", pair.publicKey)).toString("base64");

console.log("=== PRIVATE JWK (secret ENTITLEMENT_SIGNING_JWK) ===");
console.log(JSON.stringify(privateJwk));
console.log("\n=== PUBLIC KEY (gradle ENTITLEMENT_PUBLIC_KEY, base64 X.509 SPKI) ===");
console.log(spki);
