This is a fork of maven-plugins https://github.com/evgeny-goldin/maven-plugins to solve https://github.com/evgeny-goldin/maven-plugins/issues/10.

This fork's focus is copy-maven-plugin only. All other plugins in this project are not respected.

**Supports Maven 3 and upwards**

Run `"mvn clean install"` to build and install plugins into your local Maven repository.

Upload to a local nexus registry:
1. copy-maven-plugin jar and pom artifacts (single upload action)
2. maven-common jar and pom artifcats (single upload action)
3. mojo-parent pom (single upload action)
4. maven-plugins pom (single upload action)

A copy of the documentation from http://wayback.archive.org/web/20140707010223/http://evgeny-goldin.com/w/index.php?title=Copy-maven-plugin&printable=yes is included in the sources.
