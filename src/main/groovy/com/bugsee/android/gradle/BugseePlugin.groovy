package com.bugsee.android.gradle

import com.android.SdkConstants
import com.android.build.gradle.api.ApkVariant
import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.api.BaseVariantOutput
import com.android.build.gradle.api.FeatureVariant
import com.android.build.gradle.tasks.ProcessApplicationManifest
import com.android.build.gradle.tasks.ProcessMultiApkApplicationManifest
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.xml.Namespace
import groovy.xml.XmlNodePrinter
import groovy.xml.XmlParser
import org.apache.commons.io.FilenameUtils
import org.apache.http.HttpEntity
import org.apache.http.HttpResponse
import org.apache.http.client.methods.HttpPost
import org.apache.http.client.methods.HttpPut
import org.apache.http.conn.ssl.DefaultHostnameVerifier
import org.apache.http.entity.FileEntity
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import org.apache.http.impl.client.StandardHttpRequestRetryHandler
import org.apache.http.message.BasicHeader
import org.apache.http.protocol.HTTP
import org.apache.http.util.EntityUtils
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.FileTree
import org.slf4j.helpers.BasicMarkerFactory

import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BugseePlugin implements Plugin<Project> {
    private static final String PLUGIN_NAME = 'bugsee'
    private static final String APP_TOKEN_TAG = 'com.bugsee.android.APP_TOKEN'
    private static final String BUILD_UUID_TAG = 'com.bugsee.android.BUILD_UUID'

    private static final String STRING_RESOURCE_START = "@string/"
    private static final String MIPMAP_RESOURCE_START = "@mipmap/"
    private static final String DRAWABLE_RESOURCE_START = "@drawable/"

    private boolean mDebug

    private mMarkerFactory = new BasicMarkerFactory()

    void apply(Project project) {
        project.extensions.create(PLUGIN_NAME, BugseePluginExtension)

        def debug = project.extensions.bugsee.debug
        mDebug = debug
        if (debug) project.logger.warn("Started Bugsee script")
        project.afterEvaluate {
            // "debug" setting should be initialized here, because client settings are not applied earlier.
            mDebug = project.bugsee.debug

            if (mDebug) project.logger.warn("Bugsee script afterEvaluate")
            // Make sure there's an android configuration
            if (!project.android) {
                throw new IllegalStateException('Must apply \'com.android.application\' or \'com.android.library\' first!')
            }
            if (project.android.hasProperty('applicationVariants') && project.android.applicationVariants) {
                project.android.applicationVariants.all { ApkVariant variant ->

                    try {
                        if (mDebug) project.logger.warn("Bugsee start for variant " + variant.name)
                        // Only create Bugsee tasks for proguard-enabled variants
                        if (isMappingFileAbsent(variant)) {
                            return
                        }

                        if (mDebug) project.logger.warn("Bugsee variant has obfuscation or mapping")

                        def variantName = variant.name.capitalize()

                        // Create Bugsee pre-proguard task
                        // Older versions of build plugin do not support nested closures to access owner's owner methods
                        // e.g. doLast has no access to BugseePlugin.this and find its methods
                        def executeBugseeManifestActionClosure = { executeBugseeManifestAction(project, variant) }
                        project.task("createBugsee${variantName}ProguardConfig") { Task bugseeManifestTask ->
                            doLast {
                                executeBugseeManifestActionClosure()
                            }
                            configureBugseeManifestTask(project, variant, bugseeManifestTask)
                        }

                        // Create Bugsee post-proguard task
                        def executeBugseeUploadTaskClosure = { executeBugseeUploadTask(project, variant) }
                        project.task("uploadBugsee${variantName}Mapping") { Task bugseeUploadTask ->
                            doLast {
                                executeBugseeUploadTaskClosure()
                            }
                            configureBugseeUploadTask(variant, bugseeUploadTask)
                        }

                    } catch (Exception ex) {
                        project.logger.error(mMarkerFactory.getMarker("Bugsee"), "Build variant handling failed for " + variant.name, ex)
                    }
                }
            } else if (project.android.hasProperty('featureVariants') && project.android.featureVariants) {
                project.android.featureVariants.all { FeatureVariant variant ->

                    try {
                        if (mDebug) project.logger.warn("Bugsee start for variant " + variant.name)
                        // Only create Bugsee tasks for proguard-enabled variants
                        if (isMappingFileAbsent(variant)) {
                            return
                        }

                        if (mDebug) project.logger.warn("Bugsee variant has obfuscation or mapping")

                        def variantName = variant.name.capitalize()

                        // Create Bugsee pre-proguard task
                        def executeBugseeManifestActionClosure = { executeBugseeManifestAction(project, variant) }
                        project.task("createBugsee${variantName}ProguardConfig") { Task bugseeManifestTask ->
                            doLast {
                                executeBugseeManifestActionClosure()
                            }
                            configureBugseeManifestTask(project, variant, bugseeManifestTask)
                        }

                        // Create Bugsee post-proguard task
                        def executeBugseeUploadTaskClosure = { executeBugseeUploadTask(project, variant) }
                        project.task("uploadBugsee${variantName}Mapping") { Task bugseeUploadTask ->
                            doLast {
                                executeBugseeUploadTaskClosure()
                            }
                            // Make bugseeUploadTask a part of build.
                            bugseeUploadTask.mustRunAfter "transformClassesAndResourcesWithProguardFor${variantName}"
                            project.tasks.findByPath("package${variantName}").dependsOn bugseeUploadTask
                        }

                    } catch (Exception ex) {
                        project.logger.error(mMarkerFactory.getMarker("Bugsee"), "Build variant handling failed for " + variant.name, ex)
                    }
                }
            }
        }
    }

    private void configureBugseeManifestTask(Project project, BaseVariant variant, Task bugseeManifestTask) {
        if (mDebug) project.logger.warn("Bugsee configureBugseeManifestTask")
        def variantOutput = variant.outputs.first()
        // Make bugseeManifestTask a part of build.

        // Android Gradle Plugin >= 3.3.0
        variantOutput.processManifestProvider.configure { Task processManifest ->
            bugseeManifestTask.mustRunAfter processManifest
        }
        variantOutput.processResourcesProvider.configure {
            dependsOn bugseeManifestTask
        }
        if (mDebug) project.logger.warn("Bugsee configured BugseeManifestTask")
    }

    private void configureBugseeUploadTask(ApkVariant variant, Task bugseeUploadTask) {
        // Make bugseeUploadTask a part of build.

        // Android Gradle Plugin >= 3.3.0
        variant.packageApplicationProvider.configure { Task packageApplication ->
            bugseeUploadTask.mustRunAfter packageApplication
        }
        variant.assembleProvider.configure {
            dependsOn bugseeUploadTask
        }
    }

    private boolean isMappingFileAbsent(BaseVariant variant) {
        try {
            // Android Gradle Plugin >= 3.6.0
            return variant.mappingFileProvider.map { it.empty }.getOrElse(true)
        } catch (Exception ignored) {
            return true
        }
    }

    void executeBugseeManifestAction(Project project, BaseVariant variant) {
        if (!variant.outputs || variant.outputs.size() == 0) {
            project.logger.warn("No outputs found for variant " + variant.name)
            return
        }

        if (mDebug) project.logger.warn("Bugsee manifestTask. Processing variant flavor: $variant.flavorName; build type: $variant.buildType.name")
        def buildUUID = UUID.randomUUID().toString()
        // Process all variant outputs. It is necessary when several apks are generated at a time (when "split" block is used).
        for (def output : variant.outputs) {
            // Find the processed manifest for this output
            def manifestFile = getManifestFile(project, output)

            if (!manifestFile || !manifestFile.isFile()) {
                project.logger.warn("Can't get manifest for variant flavor: $variant.flavorName; build type: $variant.buildType.name; output: $output.name")
                return
            }

            // No split or manifest path is not correct (occurs if manifest file was not deleted after build without split).
            if (manifestFile.parentFile.name.equals(variant.buildType.name)) {
                if (mDebug) project.logger.warn("No split or manifest path is not correct.")
                if (!addBuildUuidToManifest(project, manifestFile, buildUUID)) {
                    project.logger.warn("Application section not found in manifest for variant flavor: $variant.flavorName; build type: $variant.buildType.name; output: $output.name")
                }

                // Iterate nested directories.
                def manifestDirs = manifestFile.parentFile.listFiles(new FileFilter() {
                    @Override
                    boolean accept(File file) {
                        def files = file.list()
                        return (files && files.contains("AndroidManifest.xml"))
                    }
                })

                for (def manifestDir : manifestDirs) {
                    if (!addBuildUuidToManifest(project, new File(FilenameUtils.concat(manifestDir.path, "AndroidManifest.xml")), buildUUID)) {
                        project.logger.warn("Application section not found in manifest for variant flavor: $variant.flavorName; build type: $variant.buildType.name; manifest dir: $manifestDir")
                    }
                }
                break
            }

            // Normal manifest place for projects with split.
            if (!addBuildUuidToManifest(project, manifestFile, buildUUID)) {
                project.logger.warn("Application section not found in manifest for variant flavor: $variant.flavorName; build type: $variant.buildType.name; output: $output.name")
            }
        }
    }

    boolean addBuildUuidToManifest(Project project, File manifestFile, String buildUuid) {
        // Parse the AndroidManifest.xml
        def ns = new Namespace("http://schemas.android.com/apk/res/android", "android")
        def xml = new XmlParser().parse(manifestFile)

        // Uniquely identify the build so that we can identify the proguard file.
        def application = xml.application[0]
        if (application) {
            if (mDebug) project.logger.warn("Adding buildUUID: $buildUuid to $manifestFile.parentFile.name")
            def metaDataTags = application['meta-data']
            // remove any old BUILD_UUID tags
            def buildUuidTags = metaDataTags.findAll {
                it.attributes()[ns.name].equals(BUILD_UUID_TAG)
            }.each {
                it.parent().remove(it)
            }

            application.appendNode('meta-data', [(ns.name.getQualifiedName()): BUILD_UUID_TAG, (ns.value.getQualifiedName()): buildUuid])

            def writer = new FileWriter(manifestFile)
            def printer = new XmlNodePrinter(new PrintWriter(writer))
            printer.preserveWhitespace = true
            printer.print(xml)
            return true
        }
        return false
    }

    File getManifestFile(Project project, BaseVariantOutput variantOutput) {
        // Looks like this block below gets wrong manifest
        try { // Android Gradle Plugin >= 4.1.0
            def provider = variantOutput.processManifestProvider.get()

            // it can be a ProcessMultiApkApplicationManifest
            if (provider instanceof ProcessMultiApkApplicationManifest) {
                def multiProvider = (ProcessMultiApkApplicationManifest) provider
                def manifestPathString = com.android.utils.FileUtils.join(variantOutput.dirName, SdkConstants.ANDROID_MANIFEST_XML)
                def mergedManifestOutputFile = new File(multiProvider.multiApkManifestOutputDirectory.get().asFile, manifestPathString)
                if (mDebug) project.logger.warn("[Bugsee getManifestFile] ProcessMultiApkApplicationManifest")
                return mergedManifestOutputFile
            }

            // or a ProcessApplicationManifest
            if (provider instanceof ProcessApplicationManifest) {
                def processProvider = (ProcessApplicationManifest) provider
                if (mDebug) project.logger.warn("[Bugsee getManifestFile] ProcessApplicationManifest")
                return processProvider.mergedManifest.get().asFile
            }
        } catch (Error ignored) {
            if (mDebug) project.logger.warn("[Bugsee getManifestFile] Attempt 1 to locate manifest file failed. Error: " + ignored.getMessage())
        } catch (Exception ignored) {
            if (mDebug) project.logger.warn("[Bugsee getManifestFile] Attempt 1 to locate manifest file failed")
        }

        try {
            String outString = getManifestOutputString(project, variantOutput)
            if (outString?.endsWith(".xml")) {
                return new File(outString)
            }
            File manifestPath = new File(outString,"AndroidManifest.xml")
            if (!manifestPath.isFile()) {
                manifestPath = new File(
                    new File(outString, variantOutput.dirName),"AndroidManifest.xml")
                if (manifestPath.isFile()) {
                    return manifestPath
                }
            }
        } catch (Throwable ignored) {
            if (mDebug) project.logger.warn("[Bugsee getManifestFile] Attempt 2 to locate manifest file failed: ${ignored.getMessage()}")
        }

        try {
            return variantOutput.processManifestProvider.get().manifestOutputFile
        } catch (Throwable ignored) {
            if (mDebug) project.logger.warn("[Bugsee getManifestFile] Attempt 3 to locate manifest file failed: ${ignored.getMessage()}")
        }

        return null
    }

    // Can return manifest output file or directory path depending on Android Gradle Plugin version.
    static String getManifestOutputString(Project project, BaseVariantOutput variantOutput) {
        // Android Gradle Plugin >= 3.3.0
        def outDir = variantOutput.processManifestProvider.get().manifestOutputDirectory

        if (outDir instanceof String)
            return outDir

        if (outDir instanceof File)
            return outDir.getPath()

        // Android Gradle Plugin >= 3.3.0
        FileTree fileTree = outDir.getAsFileTree()
        File manifestFile = fileTree.filter { File f -> f.name == "AndroidManifest.xml" }.first()
        return manifestFile?.getPath()
    }

    void executeBugseeUploadTask(Project project, BaseVariant variant) {
        // Find the processed manifest for this variant
        if (mDebug) project.logger.warn("Bugsee upload task. Processing project: $project; variant flavor: $variant.flavorName; build type: $variant.buildType.name")
        def manifestPath = getManifestFile(project, variant.outputs[0])
        if (!manifestPath) {
            project.logger.warn("Can't get manifest for variant flavor: " + variant.flavorName)
            return
        }

        // Parse the AndroidManifest.xml
        def ns = new Namespace("http://schemas.android.com/apk/res/android", "android")
        def xml = new XmlParser().parse(manifestPath)

        // Get the Bugsee API key
        NodeList metaDataTags = xml.application['meta-data']
        String appToken = getAppToken(project, variant, ns, metaDataTags)
        if (appToken == null) {
            project.logger.warn("Could not get appToken.")
            return
        }

        if (mDebug) project.logger.warn("appToken is " + appToken)
        // Uniquely identify the build so that we can identify the proguard file.
        def buildUUID
        def buildUUIDTags = metaDataTags.findAll {
            it.attributes()[ns.name].equals(BUILD_UUID_TAG)
        }
        if (buildUUIDTags.size() == 0) {
            project.logger.warn("Could not find '$BUILD_UUID_TAG' <meta-data> tag in your AndroidManifest.xml")
            return
        } else {
            buildUUID = buildUUIDTags[0].attributes()[ns.value]
        }

        // Get the build version
        def versionName = xml.attributes()[ns.versionName]
        def versionCode = xml.attributes()[ns.versionCode]
        if (versionCode == null) {
            project.logger.warn("Could not find 'android:versionCode' value in your AndroidManifest.xml")
            return
        }

        File zipTemp = getZipDataToUpload(project, variant, xml, ns, buildUUID)
        if (!zipTemp)
            return

        if (mDebug) project.logger.warn("Bugsee Upload task step 1.")
        String mappingHash = getHash(getMappingFile(variant).text)
        // Upload the mapping file to Bugsee
        String json = JsonOutput.toJson([uuid: buildUUID, version: versionName, build: versionCode, hash: mappingHash])
        uploadData(project, zipTemp, json, appToken)
    }

    String getHash(String text) {
        MessageDigest md = MessageDigest.getInstance("SHA-1")
        md.update(text.getBytes("UTF-8"))

        byte[] result = md.digest()
        return String.format("%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x",
            result[0], result[1], result[2], result[3],
            result[4], result[5], result[6], result[7],
            result[8], result[9], result[10], result[11],
            result[12], result[13], result[14], result[15],
            result[16], result[17], result[18], result[19])
    }

    File getZipDataToUpload(Project project, BaseVariant variant, Node manifestXml, Namespace namespace, String buildUUID) {
        // Find the Proguard mapping file
        File mappingFile = getMappingFile(variant)

        // If proguard configuration includes -dontobfuscate, the mapping file
        // will not exist (but we also won't need it).
        if (!mappingFile.exists()) {
            return null
        }

        if (mDebug) project.logger.warn("Bugsee Upload task step 0 (found mapping file). buildUUID: " + buildUUID)
        // Zip the file
        def zipTemp = File.createTempFile(buildUUID, 'zip')
        zipTemp.deleteOnExit()
        def zos = new ZipOutputStream(new FileOutputStream(zipTemp))
        zos.withStream {
            if (mDebug) project.logger.warn("Created temp zip: $zipTemp")
            // Add mapping file
            zos.putNextEntry(new ZipEntry('mapping.txt'))
            def mappingFileFis = new FileInputStream(mappingFile)
            mappingFileFis.withStream { copyFiles(mappingFileFis, zos) }
            zos.closeEntry()
            if (mDebug) project.logger.warn("Added mapping file")
            // Add icon file
            Node application = manifestXml.application[0]
            if (application) {
                def iconResourceId = application.attribute(namespace.icon)
                if (iconResourceId) {
                    if (mDebug) project.logger.warn("Icon resource id: " + iconResourceId)
                    File icon = getIcon(project, iconResourceId)
                    if (mDebug) project.logger.warn("Chosen icon file: " + icon?.path)
                    if (icon) {
                        def iconFileExtension = FilenameUtils.getExtension(icon.getName())
                        zos.putNextEntry(new ZipEntry('icon.' + iconFileExtension))
                        def iconFis = new FileInputStream(icon)
                        iconFis.withStream { copyFiles(iconFis, zos) }
                        zos.closeEntry()
                    }
                } else {
                    project.logger.warn("Didn't find app icon.")
                }
            }
        }
        return zipTemp
    }

    private File getMappingFile(BaseVariant variant) {
        // Android Gradle Plugin >= 3.6.0
        return variant.mappingFileProvider.get().first()
    }

    /**
     * Cloned private method {@link java.nio.file.Files#copy(java.io.InputStream, java.io.OutputStream)}
     * Reads all bytes from an input stream and writes them to an output stream.
     */
    private static long copyFiles(InputStream source, OutputStream sink) throws IOException {
        long nread = 0L
        byte[] buf = new byte[8192]
        int n
        while ((n = source.read(buf)) > 0) {
            sink.write(buf, 0, n)
            nread += n
        }
        return nread
    }

    /**
     *
     * @param project
     * @param file file to upload. It is deleted after uploading.
     * @param json
     * @param appToken
     */
    void uploadData(Project project, File file, String json, String appToken) {
        if (mDebug) project.logger.warn("Starting to send data. Body: $json")
        // 1. Create request, get presigned url
        HttpPost httpPost = new HttpPost(project.bugsee.endpoint + '/apps/' + appToken + '/symbols')
        StringEntity body = new StringEntity(json)
        body.setContentType(new BasicHeader(HTTP.CONTENT_TYPE, "application/json"))
        httpPost.setEntity(body)

        CloseableHttpClient httpClient = HttpClients.custom()
            .setSSLHostnameVerifier(new DefaultHostnameVerifier(null))
            .setRetryHandler(new StandardHttpRequestRetryHandler())
            .build()

        HttpResponse response = httpClient.execute(httpPost)

        if (response.getStatusLine().getStatusCode() != 200) {
            project.logger.warn("Bugsee upload failed: " + EntityUtils.toString(response.getEntity(), "utf-8"))
            return
        }

        HttpEntity resEntity = response.getEntity()

        if (resEntity == null) {
            project.logger.warn("Bugsee upload failed: no response from server", "utf-8")
            return
        }

        String contentText = resEntity.content.text
        if (mDebug) project.logger.warn("Bugsee Upload task step 2. Content text: " + contentText)
        def jsonSlurper = new JsonSlurper()
        def responseBody = jsonSlurper.parseText(contentText)

        if (responseBody.code && responseBody.code == 16004) {
            // This mapping has been already uploaded. Starting from Gradle 3.0 mapping is re-generated only on code changes.
            if (mDebug) project.logger.warn("Got SymbolAlreadyExistsError from server")
            return
        }
        // Check responseBody.endpoint
        if (!responseBody.endpoint) {
            if (responseBody.error) {
                String errorType = responseBody.error.type
                if ("ApplicationNotFoundError".equals(errorType)) {
                    project.logger.warn("App token is invalid: " + appToken)
                } else {
                    project.logger.warn("Bugsee upload failed with error: " + responseBody.error)
                }
            } else { // No responseBody.error
                project.logger.warn("Bugsee upload failed: null endpoint")
            }
            return
        }

        // 2. Upload to presigned URL
        if (mDebug) project.logger.warn("Endpoint: " + responseBody.endpoint)
        HttpPut httpPut = new HttpPut(responseBody.endpoint)
        httpPut.setEntity(new FileEntity(file, ""))
        response = httpClient.execute(httpPut)

        if (response.getStatusLine().getStatusCode() != 200) {
            project.logger.warn("Bugsee upload failed: " + EntityUtils.toString(response.getEntity(), "utf-8"))
            return
        }

        file.delete()
        if (mDebug) project.logger.warn("Bugsee Upload task finish.")
    }

    String getAppToken(Project project, BaseVariant variant, Namespace androidNamespace, NodeList appMetaData) {
        // Check closure, which chooses app token for concrete build variant.
        if (project.bugsee.getAppTokenByVariant()) {
            def variantAppToken = project.bugsee.getAppTokenByVariant()(variant)
            if (variantAppToken) {
                if (mDebug) project.logger.warn("Use appTokenByVariant: " + variantAppToken)
                return variantAppToken
            }
        }
        // Check AppTokenProvider.
        if (project.bugsee.getAppTokenProvider()) {
            def variantAppToken = project.bugsee.getAppTokenProvider().getAppToken(variant)
            if (variantAppToken) {
                if (mDebug) project.logger.warn("Use app token, taken from AppTokenProvider: " + variantAppToken)
                return variantAppToken
            }
        }
        // Check default app token.
        if (project.bugsee.getDefaultAppToken()) { // This check is equivalent to (value != null && value != "")
            if (mDebug) project.logger.warn("Use project.bugsee.defaultAppToken: " + project.bugsee.getDefaultAppToken())
            return project.bugsee.getDefaultAppToken()
        } else {
            def appTokenTags = appMetaData.findAll {
                it.attributes()[androidNamespace.name].equals(APP_TOKEN_TAG)
            }
            if (appTokenTags.size() == 0) {
                project.logger.warn("Could not find '$APP_TOKEN_TAG' <meta-data> tag in your AndroidManifest.xml")
                return null
            }
            def appToken = appTokenTags[0].attributes()[androidNamespace.value]
            if (!appToken) {
                project.logger.warn("App token is null.")
                return null
            }

            if (appToken.startsWith(STRING_RESOURCE_START))
                return getStringResource(project, appToken)

            return appToken
        }
    }

    String getStringResource(Project project, String resourceIdString) {
        String resourceId = resourceIdString.substring(STRING_RESOURCE_START.length())
        if (!resourceId) {
            project.logger.warn("Invalid string resource name specified: " + resourceIdString)
            return null
        }

        def stringResourceFiles = project.android.sourceSets.main.res.sourceFiles.findAll {
            it.name.equals 'strings.xml'
        }
        if (mDebug) project.logger.warn("resourceId: " + resourceId)

        for (int i = 0; i < stringResourceFiles.size(); i++) {
            def currentXml = new XmlSlurper().parse(stringResourceFiles.get(i))
            def value = currentXml.string.find { resourceId.equals(it.attributes()['name']) }
            if (value)
                return value.text()
        }

        project.logger.warn("Could not find " + resourceIdString + " string resource")
        return null
    }

    // Tries to get xxhdpi icon, because it has the most suitable size for us (144*144). If xxhdpi icon is not found, get the largest icon.
    File getIcon(Project project, String resourceIdString) {
        String resourceStart
        if (resourceIdString.startsWith(MIPMAP_RESOURCE_START)) {
            resourceStart = MIPMAP_RESOURCE_START
        } else if (resourceIdString.startsWith(DRAWABLE_RESOURCE_START)) {
            resourceStart = DRAWABLE_RESOURCE_START
        } else return null

        String resourceId = resourceIdString.substring(resourceStart.length())
        if (!resourceId)
            return null

        String resourceFolderType = resourceStart.substring(1, resourceStart.length() - 1)
        if (mDebug) project.logger.warn("Icon resourceFolderType: " + resourceFolderType)

        List<File> iconFiles = project.android.sourceSets.main.res.sourceFiles.findAll {
            // We don't handle case, when specified icon resource has xml type (for example, selector).
            // We get files only from folders of specified type. For example, if specified resource is mipmap, we don't consider drawable with the same name.
            FilenameUtils.getBaseName(it.name).equals(resourceId) && !FilenameUtils.getExtension(it.name).equals('xml') && it.getParent().toLowerCase(Locale.ENGLISH).contains(resourceFolderType)
        }
        if (!iconFiles || iconFiles.size() == 0)
            return null
        // Try to find xxhdpi icon.
        File xxhdpiFile = iconFiles.find { it.getParent().contains("xxhdpi") }
        if (xxhdpiFile)
            return xxhdpiFile
        // Get the largest icon.
        iconFiles.sort { left, right -> left.size() <=> right.size() }
        return iconFiles.last()
    }
}
