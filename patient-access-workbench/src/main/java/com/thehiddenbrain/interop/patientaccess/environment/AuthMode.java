package com.thehiddenbrain.interop.patientaccess.environment;

/** How the workbench obtains the bearer token it sends to the FHIR server. */
public enum AuthMode {
    /** Open server (demo, sandboxes). */
    NONE,
    /** A token pasted by the tester (or obtained elsewhere), sent as is. */
    STATIC_TOKEN,
    /** OAuth 2.0 client credentials with client id + secret at the token endpoint. */
    CLIENT_CREDENTIALS,
    /** SMART Backend Services: client credentials with a signed JWT client assertion (RS384/ES384). */
    BACKEND_SERVICES,
    /** SMART App Launch standalone launch: authorization code + PKCE through the member's browser login. */
    SMART_AUTHORIZATION_CODE
}
