package com.thehiddenbrain.interop.extract.partners;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A vendor as an MFT partner: routes, agreement status, contract dates, encryption ownership and API access. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Partner {

    public String code;
    public String name;
    public String category;
    public String contactEmail;
    public String status = "ACTIVE";
    public boolean baaOnFile;
    public String baaSignedDate;
    public String contractStart;
    public String contractEnd;
    /** AXWAY, APP or NONE. */
    public String pgpBy = "AXWAY";
    public String pgpKeyId;
    public String pgpKeyExpires;
    public Route test = new Route();
    public Route prod = new Route();
    public boolean apiEnabled;
    public String apiKey;
    public boolean allowUnmaskedSamples;
    public String notes;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Route {
        /** DROP_FOLDER or AXWAY_REST. */
        public String mode = "DROP_FOLDER";
        public String folder;
        public String axwayAccount;
        public boolean ackExpected = true;
        public String transport = "SFTP";
    }
}
