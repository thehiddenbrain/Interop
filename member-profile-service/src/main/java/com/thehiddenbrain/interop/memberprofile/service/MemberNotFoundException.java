package com.thehiddenbrain.interop.memberprofile.service;

public class MemberNotFoundException extends RuntimeException {

    public MemberNotFoundException(String memberId) {
        super("Member '" + memberId + "' not found");
    }
}
