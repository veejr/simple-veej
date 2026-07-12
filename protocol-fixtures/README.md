# Protocol fixtures

This directory contains non-secret, machine-readable cryptographic vectors
published by `veejr-server`. Required vectors are listed in section 17 of the
v1 client protocol. `v1.json` is copied byte-for-byte from the server
repository and validated by `core:crypto` tests.

Fixtures must cover browser-to-Android and Android-to-browser interoperability.
Production secrets must never be used to generate them.

The fixed private keys in these fixtures are public test data. Never use them
for an account or production content.
