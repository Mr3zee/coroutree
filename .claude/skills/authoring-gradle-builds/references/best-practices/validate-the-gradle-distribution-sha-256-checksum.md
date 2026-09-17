# Validate the Gradle Distribution SHA-256 Checksum
Set `distributionSha256Sum` in `gradle-wrapper.properties` to verify the integrity of the downloaded Gradle distribution.  

## Explanation
Always set the `distributionSha256Sum` property in your `gradle-wrapper.properties` file to verify the integrity of the downloaded Gradle distribution. This ensures the `gradle-X.X-bin.zip` file matches the official SHA-256 checksum published by Gradle, protecting your build from corruption or tampering.  

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-8.6-bin.zip
distributionSha256Sum=2b3f4...sha256-here...f4511
```

This validation step enhances security by preventing the execution of compromised or incomplete Gradle distributions.  
The official SHA-256 checksums can be found on the [Gradle releases page](https://gradle.org/releases/).  

## References
* [Gradle Releases with Checksums](https://gradle.org/releases/)

* [Gradle Wrapper Reference (Use `gradle_docs(path="userguide/gradle_wrapper.md")`.)

---

For the most up-to-date guidance, use `gradle_docs` with `tag:best-practices`.
