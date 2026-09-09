# Form template provenance

`cms1500-02-12.pdf` is the CMS-1500 Health Insurance Claim Form, revision 02/12,
approved by the National Uniform Claim Committee (NUCC, OMB-0938-1197). The form
itself is a U.S. government / NUCC form. This fillable (AcroForm) copy was taken
from the MIT-licensed npm package `cms1500-react` 1.2.1 (`assets/cms-1500-template.pdf`).
It contains the same 02/12 layout Cigna distributes.

Page 2 of the template (the printed instructions / back of form) is dropped at
generation time; only the claim page is emitted.

To use a different template (for example a payer-specific copy of the same NUCC
form) point `cms1500.template` at it. The field names must match those listed in
`Cms1500Fields.java`.
