# Consider use of `@Incubating` APIs carefully
Use Incubating APIs deliberately and with awareness of trade-offs. New or unstable Incubating features can require updates even on minor Gradle upgrades, while some features are close to Stable and already widely adopted. Weigh the benefits against the likely increase in maintenance effort.  

## Explanation
Compared to many other ecosystems, Gradle is very strict with Incubating features. As long as there are outstanding issues related to the feature, it will not be promoted to the Stable state. An Incubating state means that breaking changes can happen in non-major releases. However, the closer the feature is to being Stable, the less likely it is to change. There are a number of features that are already widely adopted and recommended as best practices, even though they are still incubating, like [`dependencyResolutionManagement.repositories`](https://github.com/gradle/gradle/issues/32443).  
When considering using an Incubating feature, the best approach is to check its maturity and stability:  
1. Check which Gradle version introduced it.

2. Check related GitHub issues, how many are linked, and whether any would affect you.

If the feature is new or still has many linked issues, consider its use carefully. It may introduce an additional maintenance cost for your build setup, as you may need to update your build scripts even on minor version updates.  

|---|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
|   | Remember that the goal of Incubating features is to put them in users' hands early to gather feedback and guide their evolution. If you are using such features, please provide feedback and report bugs if you encounter any. |

## References
* [The Feature Lifecycle (Use `gradle_docs(path="userguide/feature_lifecycle.md")`.)

* [Best Practice - Set up your Dependency Repositories in the Settings file (Use `gradle_docs(path="userguide/best_practices_dependencies.md")`.)

---

For the most up-to-date guidance, use `gradle_docs` with `tag:best-practices`.
