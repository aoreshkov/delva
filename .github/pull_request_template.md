<!-- What the change does and why. Link the issue it resolves, e.g. "Fixes #12".
     The checklist abbreviates the rules in .github/CONTRIBUTING.md, under "Pull requests". -->

- [ ] Tests that fail without the change
- [ ] `./gradlew check` passes
- [ ] Public API changed: KDoc written and `./gradlew updateKotlinAbi` committed
- [ ] Observable behavior changed: `project/SPEC.md` (and the guide or module README) updated
- [ ] `CHANGELOG.md` entry under `Unreleased`
