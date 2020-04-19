FROM quay.io/maxandersen/jbang-action

ADD depfetch.java /depfetch.java
RUN /jbang/bin/jbang /depfetch.java

ADD epic_maker.java /epic_maker.java

RUN GITHUB_TOKEN=blah GITHUB_EVENT_PATH=fake BOTNAME=fake /jbang/bin/jbang epic_maker.java init

ENTRYPOINT ["/jbang/bin/jbang", "/epic_maker.java"]
