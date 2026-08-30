pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository {
                maven {
                    name = "ThanosApolloSmack"
                    url = uri("https://maven.thanosapollo.org/releases")
                    mavenContent { releasesOnly() }
                }
            }
            filter { includeGroup("org.thanosapollo.smack") }
        }
    }
}

rootProject.name = "Nema"
include(":app")
