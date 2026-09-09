package com.thehiddenbrain.interop.cms1500.service;

import com.thehiddenbrain.interop.cms1500.contract.ViolationDetail;

import java.util.List;

/** Outcome of validating a claim without generating anything. */
public record ValidationReport(boolean valid, List<ViolationDetail> errors, List<String> warnings) {
}
