/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.tasks

import org.apache.commons.io.FilenameUtils
import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.test.fixtures.archive.ArchiveTestFixture
import org.gradle.test.fixtures.archive.TarTestFixture
import org.gradle.test.fixtures.archive.ZipTestFixture
import org.gradle.test.fixtures.file.TestFile
import spock.lang.Unroll

@Unroll
class ReproducibleArchivesIntegrationTest extends AbstractIntegrationSpec {

    def "reproducible #taskName for directory - #files"() {
        given:
        files.each {
            file("from/${it}").text = it
        }
        buildFile << """
            task ${taskName}(type: ${taskType}) {
                reproducibleFileOrder = true
                preserveFileTimestamps = false
                from 'from'
                destinationDir = buildDir
                archiveName = 'test.${fileExtension}'
            }
            """

        when:
        succeeds taskName

        then:
        file("build/test.${fileExtension}").md5Hash == expectedHash

        where:
        input << [
            ['dir1/file11.txt', 'dir2/file22.txt', 'dir3/file33.txt'].permutations(),
            ['zip', 'tar']
        ].combinations()
        files = input[0]
        taskName = input[1]
        taskType = taskName.capitalize()
        fileExtension = taskName
        expectedHash = taskName == 'tar' ? 'b99ef9e1ce3d2f85334a4a23a2620932' : 'cecc57bfa8747b4f39fa4a5e1c0dbd31'
    }

    def "timestamps are ignored in #taskName"() {
        given:
        createTestFiles()
        buildFile << """
            task ${taskName}(type: ${taskType}) {
                reproducibleFileOrder = true
                preserveFileTimestamps = false
                from 'dir1'
                destinationDir = buildDir
                archiveName = 'test.${fileExtension}'
            }
            """

        when:
        succeeds taskName

        def archive = file("build/test.${fileExtension}")
        then:
        def firstFileHash = archive.md5Hash

        when:
        file('dir1/file11.txt').makeOlder()
        archive.delete()
        succeeds taskName

        then:
        archive.md5Hash == firstFileHash

        where:
        taskName << ['tar', 'zip']
        taskType = taskName.capitalize()
        fileExtension = taskName
    }

    def "#compression compressed tar files are reproducible"() {
        given:
        createTestFiles()
        buildFile << """
            task tar(type: Tar) {
                reproducibleFileOrder = true
                preserveFileTimestamps = false  
                compression = '${compression}'
                from 'dir1', 'dir2', 'dir3'
                destinationDir = buildDir
                archiveName = 'test.tar.${compression}'
            }
            """

        when:
        succeeds 'tar'

        then:
        file("build/test.tar.${compression}").md5Hash == md5

        where:
        compression | md5
        'gzip'      | '9981efce12fd025835922ce0f2961ab9'
        'bzip2'     | '60f165136a27358d02926f26fb184c86'
    }

    def "#taskName preserves order of child specs"() {
        given:
        createTestFiles()
        buildFile << """
            task ${taskName}(type: ${taskType}) {
                reproducibleFileOrder = true
                preserveFileTimestamps = false
                from('dir2') {
                    into 'dir2'
                }
                from('dir1') {
                    into 'dir1'
                }     
                from 'dir1/file13.txt'
                from 'dir1/file11.txt'
                destinationDir = buildDir
                archiveName = 'test.${fileExtension}'
            }
        """

        when:
        succeeds taskName

        then:
        archive(file("build/test.${fileExtension}")).relativePaths == [
            'file13.txt',
            'file11.txt',
            'dir2/file21.txt',
            'dir2/file22.txt',
            'dir2/file23.txt',
            'dir2/file24.txt',
            'dir1/file11.txt',
            'dir1/file12.txt',
            'dir1/file13.txt',
            'dir1/file14.txt'
        ]

        where:
        taskName << ['tar', 'zip']
        taskType = taskName.capitalize()
        fileExtension = taskName
    }

    def "#taskName can use zipTree and tarTree"() {
        given:
        createTestFiles()
        buildFile << """
            task aTar(type: Tar) {
                from('dir1')
                destinationDir = buildDir
                archiveName = 'test.tar'
            }
            task aZip(type: Zip) {
                from('dir2')
                destinationDir = buildDir
                archiveName = 'test.zip'
            }

            task ${taskName}(type: ${taskType}) {
                reproducibleFileOrder = true
                preserveFileTimestamps = false
                destinationDir = buildDir
                archiveName = 'combined.${fileExtension}'

                from zipTree(aZip.archivePath)
                from tarTree(aTar.archivePath)
                dependsOn aZip, aTar
            }
        """

        when:
        succeeds taskName

        then:
        archive(file("build/combined.${fileExtension}")).relativePaths == [
            'file21.txt',
            'file22.txt',
            'file23.txt',
            'file24.txt',
            'file11.txt',
            'file12.txt',
            'file13.txt',
            'file14.txt',
        ]

        where:
        taskName << ['zip', 'tar']
        taskType = taskName.capitalize()
        fileExtension = taskName
    }

    def "#taskName uses only first duplicate"() {
        given:
        duplicateEntriesInArchive(taskName, taskType, fileExtension)

        buildFile << """
            ${taskName} {
                duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            }
        """

        when:
        succeeds taskName

        then:
        archive(file("build/test.${fileExtension}")).content('test.txt') == "from dir2"

        where:
        taskName << ['zip', 'tar']
        taskType = taskName.capitalize()
        fileExtension = taskName
    }

    def "#taskName can fail for duplicate entries"() {
        given:
        duplicateEntriesInArchive(taskName, taskType, fileExtension)

        buildFile << """
            ${taskName} {
                duplicatesStrategy = DuplicatesStrategy.FAIL
            }
        """

        when:
        fails taskName

        then:
        failure.assertHasCause('Encountered duplicate path "test.txt" during copy operation configured with DuplicatesStrategy.FAIL')

        where:
        taskName << ['zip', 'tar']
        taskType = taskName.capitalize()
        fileExtension = taskName
    }

    def "#taskName supports filtered entries"() {

        given:
        file('dir1/test.txt').text = "Hello"
        buildFile << """
        task ${taskName}(type: ${taskType}) {
            reproducibleFileOrder = true
            preserveFileTimestamps = false
            destinationDir = buildDir
            archiveName = 'test.${fileExtension}'

            from('dir1') {
                filter { 'Goodbye' }
            }
        }
        """

        when:
        succeeds taskName

        then:
        archive(file("build/test.${fileExtension}")).content('test.txt') == 'Goodbye'

        where:
        taskName << ['zip', 'tar']
        taskType = taskName.capitalize()
        fileExtension = taskName
    }

    def "#taskName sorts by target file name"() {

        given:
        createDir('dir1') {
            file('test1.txt') << 'test1'
            file('test2.txt') << 'test2'
        }
        buildFile << """
        task ${taskName}(type: ${taskType}) {
            reproducibleFileOrder = true
            preserveFileTimestamps = false
            destinationDir = buildDir
            archiveName = 'test.${fileExtension}'

            from('dir1') {
                rename { it == 'test1.txt' ? 'test4.txt' : 'test3.txt' }
            }
        }
        """

        when:
        succeeds taskName

        then:
        def archiveFile = archive(file("build/test.${fileExtension}"))
        archiveFile.hasDescendants('test3.txt', 'test4.txt')
        archiveFile.content('test3.txt') == 'test2'
        archiveFile.content('test4.txt') == 'test1'

        where:
        taskName << ['zip', 'tar']
        taskType = taskName.capitalize()
        fileExtension = taskName
    }

    private void duplicateEntriesInArchive(taskName, taskType, fileExtension) {
        file('dir1/test.txt') << "from dir1"
        file('dir2/test.txt') << "from dir2"

        buildFile << """
            task ${taskName}(type: ${taskType}) {
                reproducibleFileOrder = true
                preserveFileTimestamps = false
                destinationDir = buildDir
                archiveName = 'test.${fileExtension}'

                from 'dir2'
                from 'dir1'
            }
        """
    }

    ArchiveTestFixture archive(TestFile archiveFile) {
        String type = FilenameUtils.getExtension(archiveFile.name)
        if (type == 'zip') {
            new ZipTestFixture(archiveFile)
        } else {
            new TarTestFixture(archiveFile)
        }
    }

    def createTestFiles() {
        createDir('dir1') {
            file('file12.txt') << 'test2'
            file('file11.txt') << 'test1'
            file('file13.txt') << 'test3'
            file('file14.txt') << 'test4'
        }
        createDir('dir2') {
            file('file22.txt') << 'test2'
            file('file21.txt') << 'test1'
            file('file23.txt') << 'test3'
            file('file24.txt') << 'test4'
        }
        createDir('dir3') {
            file('file32.txt') << 'test2'
            file('file31.txt') << 'test1'
            file('file33.txt') << 'test3'
            file('file34.txt') << 'test4'
        }
    }
}
