/*
 * Copyright (c) 2022 dzikoysk
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.reposilite.maven

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT
import com.fasterxml.jackson.dataformat.xml.JacksonXmlModule
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.reposilite.maven.api.GeneratePomRequest
import com.reposilite.maven.api.LatestVersionResponse
import com.reposilite.maven.api.METADATA_FILE
import com.reposilite.maven.api.Metadata
import com.reposilite.maven.api.SaveMetadataRequest
import com.reposilite.maven.api.Versioning
import com.reposilite.maven.api.VersionsResponse
import com.reposilite.storage.VersionComparator
import com.reposilite.storage.api.Location
import com.reposilite.web.http.ErrorResponse
import com.reposilite.web.http.internalServer
import com.reposilite.web.http.notFound
import com.reposilite.web.http.unauthorizedError
import panda.std.Result
import panda.std.letIf
import panda.std.mapToUnit
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal class MetadataService(private val repositorySecurityProvider: RepositorySecurityProvider) {

    private val xml = XmlMapper.xmlBuilder()
        .addModules(JacksonXmlModule(), kotlinModule())
        .configure(ToXmlGenerator.Feature.WRITE_XML_DECLARATION, true)
        .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .defaultUseWrapper(false)
        .enable(INDENT_OUTPUT)
        .build()

    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    fun saveMetadata(saveMetadataRequest: SaveMetadataRequest): Result<Metadata, ErrorResponse> =
        with (saveMetadataRequest) {
            Result.attempt { xml.writeValueAsBytes(metadata) }
                .mapErr { internalServer("Cannot parse metadata file") }
                .flatMap { repository.putFile(gav.resolveMetadataFile(), it.inputStream()).map { _ -> it } }
                .flatMap { repository.writeFileChecksums(gav.resolveMetadataFile(), it) }
                .map { metadata }
        }

    /**
     * Generates POM file and appends given version to the parent metadata file
     */
    fun generatePom(generatePomRequest: GeneratePomRequest): Result<Unit, ErrorResponse> =
        with (generatePomRequest) {
            val currentDirectory = gav.getParent()
            val parentDirectory = currentDirectory.getParent()

            if (!repositorySecurityProvider.canModifyResource(accessToken, repository, parentDirectory)) {
                return unauthorizedError("Unauthorized access request")
            }

            repository
                .putFile(
                    location = gav,
                    inputStream = """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <project xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns="http://maven.apache.org/POM/4.0.0"
                            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                          <modelVersion>4.0.0</modelVersion>
                          <groupId>$groupId</groupId>
                          <artifactId>$artifactId</artifactId>
                          <version>$version</version>
                          <description>POM was generated by Reposilite</description>
                        </project>
                    """.trimIndent().trim().byteInputStream()
                )
                .map { findMetadata(repository, parentDirectory).orElseGet { Metadata() } }
                .map {
                    it.copy(
                        groupId = groupId,
                        artifactId = artifactId,
                        versioning = (it.versioning ?: Versioning()).copy(
                            latest = version,
                            release = version,
                            lastUpdated = timestampFormatter.format(ZonedDateTime.now()),
                            _versions = (it.versioning?.versions?.toMutableList() ?: mutableListOf()) + version
                        )
                    )
                }
                .flatMap {
                    saveMetadata(
                        SaveMetadataRequest(
                            repository = repository,
                            gav = parentDirectory,
                            metadata = it
                        )
                    )
                }
                .mapToUnit()
        }

    fun findMetadata(repository: Repository, gav: Location): Result<Metadata, ErrorResponse> =
        repository.getFile(gav.resolveMetadataFile())
            .map { it.use { data -> xml.readValue<Metadata>(data) } }

    fun findVersions(repository: Repository, gav: Location, filter: String?): Result<VersionsResponse, ErrorResponse> =
        repository.getFile(gav.resolveMetadataFile())
            .map { it.use { data -> xml.readValue<Metadata>(data) } }
            .map { extractVersions(it) }
            .map { (isSnapshot, versions) ->
                versions
                    .letIf(filter != null) { it.filter { version -> version.startsWith(filter!!) } }
                    .let { VersionComparator.sortStrings(it) }
                    .let { VersionsResponse(isSnapshot, it.toList()) }
            }

    fun findLatestVersion(repository: Repository, gav: Location, filter: String?): Result<LatestVersionResponse, ErrorResponse> =
        findVersions(repository, gav, filter)
            .filter({ it.versions.isNotEmpty() }, { notFound("Given artifact does not have any declared version") })
            .map { (isSnapshot, versions) -> LatestVersionResponse(isSnapshot, versions.last()) }

    private fun Location.resolveMetadataFile(): Location =
        if (endsWith(METADATA_FILE)) this else resolve(METADATA_FILE)

    private data class VersionSequence(
        val isSnapshot: Boolean = false,
        val versions: Sequence<String>,
    )

    private fun extractVersions(metadata: Metadata): VersionSequence =
        (metadata.versioning?.versions?.asSequence()?.let { VersionSequence(false, it) }
            ?: metadata.versioning?.snapshotVersions?.asSequence()?.map { it.value }?.filterNotNull()?.let { VersionSequence(true, it) })
            ?: VersionSequence(false, emptySequence())

}
