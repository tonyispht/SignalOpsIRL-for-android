# Release signing key

`release.jks.enc` is the app's permanent release keystore, encrypted with AES-256 (PBKDF2, 600,000 rounds).
It's safe to keep in the repo: it can only be unlocked with the `SIGNING_STORE_PASSWORD` repository secret,
which is a random 32-character password that also protects the keystore itself.

The build workflow decrypts it on GitHub's servers, signs the APK, and deletes it again.

**Keep a private backup of the password.** If it's lost, future versions can't be signed with the same key,
and players would have to uninstall before updating.
