#!/bin/bash

mvn --settings="../settings.xml" release:clean -DautoVersionSubmodules=true -Darguments="-DskipTests=true -Dgpg.skip=false"
mvn --settings="../settings.xml" release:prepare -DautoVersionSubmodules=true -Darguments="-DskipTests=true -Dgpg.skip=false" 
mvn --settings="../settings.xml" release:perform -DautoVersionSubmodules=true -Darguments="-DskipTests=true -Dgpg.skip=false"
