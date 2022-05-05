from threading import Thread
from time import sleep
from uuid import uuid4

from fastapi import FastAPI
from typing import Optional
import numpy as np

G = 6.67430e-11  # N m^2 / kg^2  or  m^3 / (kg s^2)
M_EARTH = 5.972e24  # kg
R_EARTH = 6.371e6  # m


class Orbit:
    """Compute, track, and evolve the orbit of a body surrounding the Earth.

    The shape of the orbit is determined by input parameters, but the orientation of the orbit is selected at random
    using three rotation matrices.

    See Thornton, Marion: Classical Dynamics, Fifth Edition, Ch. 8.7 for derivation of orbital equations.

    Parameters
    ----------
    object_mass : float [kg]
        The mass of the orbiting satellite. It must be greater than zero.
    r_min : float [m]
        The minimum (periapsis) radius of the orbiting satellite. It should be greater than 7e6m
    w_max : float [radians/s]
        The maximum (periapsis) orbital radial velocity. A circular orbit is given by:

            sqrt(G*M / r_min**3)

        Where G is Newton's constant and M is the mass of the Earth. Even small fractional deviations (anything more
        than sqrt(2)) will lead to escape velocities.
    th_0 : float [radians]
        The initial orbital position of the satellite, relative to its own orbit.
    """

    def __init__(self, object_mass: float, r_min: float, w_max: float, th_0: float):
        self.id = f"sat_{uuid4()}"
        self.mass = object_mass

        # Calculate physical parameters for the orbit.
        self.angular_momentum = object_mass * w_max * r_min ** 2
        k = G * M_EARTH * object_mass
        self.total_energy = 0.5 * object_mass * r_min ** 2 * w_max ** 2 - k / r_min
        self.alpha = self.angular_momentum ** 2 / (object_mass * k)
        self.epsilon = np.sqrt(
            1
            + 2
            * self.total_energy
            * self.angular_momentum ** 2
            / (object_mass * k ** 2)
        )

        if self.epsilon > 1:
            print("WARNING: orbit has escape velocity.")

        # Initialize the orbit.
        self.th_current = th_0
        self.r_current = self.alpha / (1 + self.epsilon * np.cos(self.th_current))

        # Select a random orientation and build the rotation matrices.
        th_x = np.random.normal() * np.pi / 6
        th_y = np.random.normal() * np.pi / 6
        th_z = np.random.normal() * np.pi / 6
        self.rotate_around_x = np.array(
            [
                [1, 0, 0],
                [0, np.cos(th_x), -np.sin(th_x)],
                [0, np.sin(th_x), np.cos(th_x)],
            ]
        )
        self.rotate_around_y = np.array(
            [
                [np.cos(th_y), 0, -np.sin(th_y)],
                [0, 1, 0],
                [np.sin(th_y), 0, np.cos(th_y)],
            ]
        )
        self.rotate_around_z = np.array(
            [
                [np.cos(th_z), np.sin(th_z), 0],
                [-np.sin(th_z), np.cos(th_z), 0],
                [0, 0, 1],
            ]
        )
        self.rotation_matrix = self.rotate_around_x.dot(
            self.rotate_around_y.dot(self.rotate_around_z)
        )

        self._running = False

        self.height = None
        self.latitude = None
        self.longitude = None

        self._thread = None

    def step(self, dt: float) -> None:
        """Take the given time step."""
        self.th_current = self.th_current + self.angular_momentum * dt / (
            self.mass * self.r_current ** 2
        )
        self.r_current = self.alpha / (1 + self.epsilon * np.cos(self.th_current))

    def run(self, dt: float):
        """Run a simulation for the given duration, with the given time steps."""
        t = 0
        self._running = True
        print(f"Starting satellite {self.id}")
        while self._running:
            # Compute the current position.
            self.step(dt)
            position = np.array(
                [
                    self.r_current * np.cos(self.th_current),
                    self.r_current * np.sin(self.th_current),
                    0,
                ]
            )
            x, y, z = self.rotation_matrix.dot(position)

            # Convert x,y,z into height above sea level, latitude, and longitude.
            h = np.linalg.norm(position) - R_EARTH

            r_xy = np.sqrt(x ** 2 + y ** 2)
            if r_xy == 0:
                latitude = np.sign(z) * np.pi / 2
            else:
                latitude = np.arctan(z / r_xy)

            if x == 0:
                longitude = np.sign(y) * np.pi / 2
            else:
                longitude = np.arctan(y / x)

            self.height = h
            self.latitude = latitude * 180 / np.pi
            self.longitude = longitude * 180 / np.pi

            t += dt
            sleep(dt - 0.05 * dt)

    def run_async(self, dt: float):
        th = Thread(target=self.run, args=(dt,), daemon=True)
        self._thread = th.start()

    def stop_async(self):
        self._running = False
        self._thread.join()

    def get_current(self):
        return self.height, self.latitude, self.longitude


NOISE = 0.001


def noisy(value):
    return value + value*NOISE*np.random.normal()


class Telescope:
    def __init__(self, latitude: float, longitude: float):
        self.id = f"tel_{uuid4()}"
        self.latitude = latitude
        self.longitude = longitude

    def read(self, satellite: Orbit) -> Optional[dict]:
        h, lat, long = satellite.get_current()
        if np.abs(self.latitude - lat) < 10 and np.abs(self.longitude - long) < 10:
            return {"id": satellite.id, "altitude": noisy(h), "latitude": noisy(lat), "longitude": noisy(long)}

    def json(self):
        return {"id": self.id, "latitude": self.latitude, "longitude": self.longitude}


# Start the satellites.
satellites = []
for _ in range(100):
    m = 100 * (1 + 2 * np.random.normal())
    r = 8e6 * (1 + 0.2 * np.random.normal())
    th_0 = 2 * np.pi * np.random.rand()
    w = np.sqrt(G * M_EARTH / r ** 3) * (1.05 + 0.1 * np.random.normal())
    orbit = Orbit(m, r, w, th_0)
    orbit.run_async(5)
    satellites.append(orbit)


# Place the telescopes.
telescopes = [
    Telescope(0 + 15 * np.random.normal(), 360 * np.random.rand() - 180)
    for _ in range(10)
]


# Create the extremely simple web service.
app = FastAPI()


@app.get("/telescope/{telescope_idx}")
async def get_telescope_readings(telescope_idx: int):
    print(f"Getting readings from telescope {telescope_idx}")
    tel = telescopes[telescope_idx]
    results = []
    for sat in satellites:
        if sat_position := tel.read(sat):
            results.append(sat_position)
            

    return {"satellites": results, "position": tel.json()}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="localhost", port=8333)
