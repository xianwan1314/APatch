import java.net.URI

plugins {
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.kotlin.compose.compiler) apply false
}

val managerVersionBrand = "vivo"

project.ext.set("kernelPatchRepoOwner", "xianwan1314")
project.ext.set("kernelPatchRepoName", "KernelPatch-APatch")
project.ext.set("managerVersionBrand", managerVersionBrand)

fun fetchLatestGitHubReleaseTag(owner: String, repo: String): String? {
    val apiUrl = "https://api.github.com/repos/$owner/$repo/releases/latest"
    return runCatching {
        val connection = URI.create(apiUrl).toURL().openConnection().apply {
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "APatch-Gradle")
        }
        connection.getInputStream().bufferedReader().use { it.readText() }
    }.getOrNull()?.let { response ->
        Regex(""""tag_name"\s*:\s*"([^"]+)"""")
            .find(response)
            ?.groupValues
            ?.getOrNull(1)
            ?.removePrefix("v")
    }
}

fun resolveKernelPatchVersion(owner: String, repo: String): String {
    val fallbackVersion = "0.13.2"
    val overriddenVersion = sequenceOf(
        providers.gradleProperty("kernelPatchVersion").orNull,
        System.getenv("KERNELPATCH_VERSION"),
    ).mapNotNull { it?.trim() }.firstOrNull { it.isNotEmpty() }
    if (overriddenVersion != null) {
        println("Using overridden KernelPatch version: $overriddenVersion")
        return overriddenVersion.removePrefix("v")
    }

    val latestVersion = fetchLatestGitHubReleaseTag(owner, repo)
    if (latestVersion != null) {
        println("Using latest KernelPatch release: $latestVersion")
        return latestVersion
    }

    println("Failed to resolve latest KernelPatch release, fallback to $fallbackVersion")
    return fallbackVersion
}

project.ext.set(
    "kernelPatchVersion",
    resolveKernelPatchVersion(
        project.ext.get("kernelPatchRepoOwner") as String,
        project.ext.get("kernelPatchRepoName") as String,
    )
)

val androidMinSdkVersion by extra(26)
val androidTargetSdkVersion by extra(36)
val androidCompileSdkVersion by extra(36)
val androidBuildToolsVersion by extra("36.1.0")
val androidCompileNdkVersion by extra("29.0.14206865")
val managerVersionBaseName by extra(getBaseVersionName())
val managerVersionCode by extra(getVersionCode())
val managerVersionName by extra(getVersionName())
val branchName by extra(getBranch())
fun Project.exec(command: String) = providers.exec {
    commandLine(command.split(" "))
}.standardOutput.asText.get().trim()

fun getGitCommitCount(): Int {
    return exec("git rev-list --count HEAD").trim().toInt()
}

fun getGitDescribe(): String {
    return exec("git rev-parse --verify --short HEAD").trim()
}

fun getVersionCode(): Int {
    val commitCount = getGitCommitCount()
    val major = 1
    return major * 10000 + commitCount + 200
}

fun getBranch(): String {
    return exec("git rev-parse --abbrev-ref HEAD").trim()
}

fun getBaseVersionName(): String {
    return getGitDescribe()
}

fun getVersionName(): String {
    return "${getBaseVersionName()}-$managerVersionBrand"
}

tasks.register("printVersion") {
    doLast {
        println("Version code: $managerVersionCode")
        println("Version name: $managerVersionName")
    }
}
