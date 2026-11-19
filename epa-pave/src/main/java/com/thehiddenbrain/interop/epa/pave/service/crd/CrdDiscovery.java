package com.thehiddenbrain.interop.epa.pave.service.crd;

import com.thehiddenbrain.interop.epa.pave.model.AdditionalField;
import com.thehiddenbrain.interop.epa.pave.model.ErrorView;
import com.thehiddenbrain.interop.epa.pave.model.ResolvedUrl;
import com.thehiddenbrain.interop.epa.pave.model.Shown;
import com.thehiddenbrain.interop.epa.pave.model.WireView;
import java.util.List;

/**
 * What CDS Hooks discovery returned: the services the environment actually offers. The service id
 * a CRD call needs is not known in advance, and this is how a tester finds it.
 *
 * @param services         one entry per service the response listed
 * @param additionalFields everything in the response the designed view did not use
 * @param error            set when discovery failed
 */
public record CrdDiscovery(ResolvedUrl url, List<Service> services, List<AdditionalField> additionalFields, WireView wire,
                           ErrorView error) {

    /**
     * One CDS service.
     *
     * @param prefetch the prefetch keys the service declares, each with its query template
     */
    public record Service(Shown id, Shown hook, Shown title, Shown description, List<Prefetch> prefetch) {
    }

    public record Prefetch(String key, Shown template) {
    }
}
