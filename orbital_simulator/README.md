Orbital Simulator
----------------

To install the necessary requirements:

```
pip install -r requirements.txt

```

To run:

```
python orbit_simulator.py
```

You can then query telescope readings on the local host:

```
curl http://localhost:8333/telescope/0 | json_pp
```

and get a result somthing like
```json
{
   "position" : {
      "id" : "tel_d6ae770f-7715-4118-a275-80045061a055",
      "latitude" : -1.30463693524673,
      "longitude" : -18.3629651601019
   },
   "results" : [
      {
         "altitude" : 6979360.76258187,
         "id" : "sat_a745da42-0feb-4e87-8912-e0d946a51b39",
         "latitude" : -0.958402204759621,
         "longitude" : -9.61424816628926
      },
      {
         "altitude" : 822060.442627655,
         "id" : "sat_37bf7248-a558-4103-9406-3bfce6fa37f5",
         "latitude" : 1.88650087262918,
         "longitude" : -25.4080140611562
      },
      {
         "altitude" : 1785231.96762704,
         "id" : "sat_72e3b167-e144-47e6-a371-70d9d09d78c2",
         "latitude" : -3.11613827433593,
         "longitude" : -17.7739370665678
      }
   ]
}
```

