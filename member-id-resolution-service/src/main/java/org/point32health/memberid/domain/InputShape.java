package org.point32health.memberid.domain;

/** The three shapes the parser recognises after separators are removed. */
public enum InputShape {
    /** 9 digits: a THP card / policy number. One person for TMP and SCO; a family for populations with dependents. */
    THP_9,
    /** 11 digits: a THP id with suffix (TMP stored 14 characters compacted, or a Public Plans id as stored). */
    ID_11,
    /** HP + digits: an HPHC member id. */
    HPHC
}
