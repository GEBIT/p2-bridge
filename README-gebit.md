<!--

    Copyright (c) 2007-2013 Sonatype, Inc.
    All rights reserved. This program and the accompanying materials
    are made available under the terms of the Eclipse Public License v1.0
    which accompanies this distribution, and is available at
    http://www.eclipse.org/legal/epl-v10.html

-->
# Releasing

As this plugin is "special", it requires totally different release procedure
than the one we use for "normal" projects.

### Prerequisites

* Maven 3.6.3+
* Java 8

### Prepare release
* Clone and ensure on gebit branch
```
git clone git@github.com:GEBIT/p2-bridge.git
git checkout gebit
```

* Set version on all projects to the release version (ie. was 1.2.1-SNAPSHOT, set it to 1.2.1)
```
gmvn org.eclipse.tycho:tycho-versions-plugin:1.7.0:set-version -DnewVersion=1.2.1
```

* Check that there is no trace left of previous version (e.g. 1.2.0 and 1.2.0-SNAPSHOT and 1.2.1-SNAPSHOT)
* Verify that MANIFEST.MF imported/exported packages are correct (export should have proper versions set)
* Verify that no SNAPSHOT dependencies are used (search for SNAPSHOT)
* Verify that bundle version has no ".qualifier" (search for qualifier), if any found, remove them.
* Build it
```
gmvn clean install
```

* Commit changes
```
git commit -a -m "Bumped version number to 1.2.1"
```

* Tag release
```
git tag -a 1.2.1 -m "Release 1.2.1"
```

### Perform release
* Release it
```
gmvn clean deploy -Prelease -Dgpg.skip -Dbuildsupport-staging.skip
```

* Bump version to next snapshot
```
gmvn org.eclipse.tycho:tycho-versions-plugin:1.7.0:set-version -DnewVersion=1.2.2-SNAPSHOT
```

* Check that there is no trace left of released version (e.g. 1.2.1)
* Verify that imported/exported packages are correct (those will be still 1.2.1, release version)
* Build it
```
gmvn clean install
```

* Commit changes
```
git commit -a -m "Bumped version number to 1.2.2-SNAPSHOT"
```

* Push back all (don't forget tags) to origin
```
git push
git push --tags
```

* Have a beer
