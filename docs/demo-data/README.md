# Demo data

These files are synthetic and de-identified. They are intended only for local
product demonstrations and contain no real employee, customer, or production
data.

## Scenario

- `employee-handbook.md`: access control, incident response, and data handling.
- `release-manual-v1/release-manual.md`: original release and rollback rules.
- `release-manual-v2/release-manual.md`: updated rules used to demonstrate a new
  document version and rollback.

Upload the two `release-manual.md` files as separate versions of the same
document. Version 1 freezes production releases at Friday 16:00; version 2
moves the freeze to Friday 18:00. After rolling back to version 1, asking about
the freeze time should cite the version 1 content again.
