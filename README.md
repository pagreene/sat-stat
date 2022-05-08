Final Project
=============

This is the final project for CSCI E88, Harvard Extension School.

The goal of this project is to mock data from telescopes tracking
object orbiting the Earth, and then build an Akka stream to analyze
that data.

[Simulator (Python)](orbital_simulator)
---------------------------------------

This module runs a simulation of satellites orbiting the Earth, as well as telescopes with
limited visibility. It will host a simple local web service on port 8333 to serve the
data from each telescope.


[Analysis Pipeline (Scala)](sat-stat)
-------------------------------------

This module implements an Akka streams pipeline to track and analyze the data produced
by the simulated telescopes. Dumping results into CSV files, it will alert/record
satellites that are about to de-orbit or are at risk of collision.

