#!/bin/bash

mvn -s ./settings.xml release:clean -DautoVersionSubmodules=true -Darguments="-DskipTests=true -Dgpg.skip=false"
mvn -s ./settings.xml release:prepare -DautoVersionSubmodules=true -Darguments="-DskipTests=true -Dgpg.skip=false" 
mvn -s ./settings.xml release:perform -DautoVersionSubmodules=true -Darguments="-DskipTests=true -Dgpg.skip=false"
