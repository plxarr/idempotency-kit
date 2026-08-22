## What does this change

<!-- One or two sentences: what and why. -->

## Checklist

- [ ] `mvn clean package` passes locally
- [ ] Tests added/updated for the behavior change (especially if touching the aspect or a storage adapter)
- [ ] If touching `core`, verified `redis` and `caffeine` adapters still satisfy the `IdempotencyStorage` contract
