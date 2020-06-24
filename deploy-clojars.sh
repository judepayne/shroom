clojure -A:jar shroom.jar
mvn deploy:deploy-file -Dfile=shroom.jar -DpomFile=pom.xml -DrepositoryId=clojars -Durl=https://clojars.org/repo/
