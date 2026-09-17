# Validate the Gradle Wrapper on every Upgrade
The Gradle Wrapper runs before your build logic and can deeply affect your build. You should treat any change to the Wrapper as security-sensitive.  
Validate the Wrapper JAR and distribution settings every time you upgrade Gradle.  

## Explanation
When you update Gradle, two Wrapper files typically change:  
* `gradle/wrapper/gradle-wrapper.jar`

* `gradle/wrapper/gradle-wrapper.properties` (`distributionUrl` and optionally `distributionSha256Sum`)

You should verify that:  
* The **Wrapper JAR** is the official binary published by Gradle (not tampered with).

* The **Wrapper JAR checksum** matches one of the values published at [gradle.org/release-checksums](https://gradle.org/release-checksums).

* The **distribution URL** points to the expected Gradle release.

* The **distribution checksum** (if configured via `distributionSha256Sum`) matches the release you intend to use (also listed on [gradle.org/release-checksums](https://gradle.org/release-checksums)).

Running an unverified Wrapper risks executing untrusted code before any of your build's safeguards run.  

### Do this
If you use the [`setup-gradle` action](https://github.com/marketplace/actions/build-with-gradle#the-setup-gradle-action) (version v4 or newer) for [GitHub Actions](https://github.com/features/actions), Wrapper validation will be performed automatically. The action validates the checksum of every `gradle-wrapper.jar` in your repository and fails the build if it finds any unknown Wrapper JAR.  
If you use a different GitHub Actions setup, you can use the dedicated [Gradle Wrapper validation action](https://github.com/marketplace/actions/build-with-gradle#the-wrapper-validation-action) instead.  

## References
* [Gradle Wrapper checksum verification (Use `gradle_docs(path="userguide/gradle_wrapper.md")`.)

* [Gradle release checksums](https://gradle.org/release-checksums)

---

For the most up-to-date guidance, use `gradle_docs` with `tag:best-practices`.
