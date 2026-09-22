package com.thehiddenbrain.interop.memberprofile.memberdomain;

/** MemberDomain could not be reached or answered with an error; the profile cannot be built. */
public class MemberDomainException extends RuntimeException {

    public MemberDomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
