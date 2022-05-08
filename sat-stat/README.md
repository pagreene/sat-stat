Sat Stat: Satellite Statistics
==============================

A Scala application to monitor a range of (locally imitated) telescopes through a REST service,
polling each telescope to get their readings. The stream then:

  - records the raw measurements
  - detects any de-orbits (satellites dipping below the atmosphere)
  - groups satellites into potential collision clusters

Each of these outputs is a CSV.

Setup
-----

(This is kinda klugey I know) To set up the project, open it in your preferred IDE, in particular
IntelliJ's Idea. I have included the .idea folder, so if you select `sat-stat` as your root folder,
many of the settings I use should be picked up.

You can also run `sbt compile` to compile the project.

Run
---

When set up in the IDE, a play button should appear next to `object Main extends App`. With this, you
can run the Akka pipeline.

If you try to run with `sbt`, it will execute the contents of `Main`, but then exit immediately. The
cause for this is still a mystery to me, and I spent hours trying to diagnose, to no avail. I expect
it is a niggling little detail, and easy enough to fix if you know what to do, so I decided to focus
my time and energy on actually building the application. It's more interesting for me to do, and more
interesting for you to evaluate.

Output Formats
--------------

Each file output is a CSV, without headers included. In the case of the satellite groupings, many
CSVs are produced, with the expectation that a downstream application will monitor them and process
them in a slightly more batched manner.

  - **Raw Measurements**: `telescop_id, satellite_id, altitude, latitude, longitutde`
  - **Crash Report**: `satellite_id, latitude, longitude`
  - **Collision Group**: `satellite_id, altitude, latitude, longitude`

Note that each collision group is labeled with:

```text
<time range>_<latitude range>_<longitude range>_<altitude range>.csv
```

where each range is arranged as `<min>[unit]-<max>[unit]`.