import java.net.URI

plugins {
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.kotlin.compose.compiler) apply false
}

val managerVersionBrand = "vivo"
val kernelPatchRepoOwner = "xianwan1314"
val kernelPatchRepoName = "KernelPatch-APatch"

extra.set("kernelPatchRepoOwner", kernelPatchRepoOwner)
extra.set("kernelPatchRepoName", kernelPatchRepoName)
extra.set("managerVersionBrand", managerVersionBrand)
// app/src/main/cpp/version is the single source of the KernelPatch version;
// apd/build.rs derives its copy from it as well.
extra.set("kernelPatchVersion", resolveKernelPatchVersion(kernelPatchRepoOwner, kernelPatchRepoName))

extra.set("androidMinSdkVersion", 26)
extra.set("androidTargetSdkVersion", 36)
extra.set("androidCompileSdkVersion", 37)
extra.set("androidBuildToolsVersion", "36.1.0")
extra.set("androidCompileNdkVersion", "29.0.14206865")
extra.set("managerVersionBaseName", getBaseVersionName())
extra.set("managerVersionCode", getVersionCode())
extra.set("managerVersionName", getVersionName())
extra.set("branchName", getBranch())

fun Project.exec(command: String) = providers.exec {
    commandLine(command.split(" "))
}.standardOutput.asText.get().trim()

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
    val sourceVersion = getKernelPatchVersion()
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
        println("Using latest KernelPatch release from $owner/$repo: $latestVersion")
        return latestVersion
    }

    println("Failed to resolve latest KernelPatch release, fallback to source version $sourceVersion")
    return sourceVersion
}

fun getGitCommitCount(): Int {
    return exec("git rev-list --count HEAD").trim().toInt()
}

fun getGitDescribe(): String {
    return exec("git rev-parse --verify --short HEAD").trim()
}

fun getVersionCode(): Int {
    val props = java.util.Properties().apply {
        File(rootDir, "version.properties").inputStream().use { load(it) }
    }
    val epoch = props.getProperty("managerVersionEpoch").toInt()
    return epoch + getGitCommitCount()
}

fun getKernelPatchVersion(): String {
    val header = File(rootDir, "app/src/main/cpp/version").readText()
    fun part(name: String) = Regex("""#define $name (\d+)""")
        .find(header)?.groupValues?.get(1)
        ?: error("$name not found in app/src/main/cpp/version")
    return "${part("MAJOR")}.${part("MINOR")}.${part("PATCH")}"
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
        println("Version code: ${project.extra["managerVersionCode"]}")
        println("Version name: ${project.extra["managerVersionName"]}")
    }
}
