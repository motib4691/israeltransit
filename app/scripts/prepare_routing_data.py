import argparse
import sqlite3
from pathlib import Path


def table_columns(conn, table):
    rows = conn.execute(f"PRAGMA table_info({table})").fetchall()
    return {row[1] for row in rows}


def get_format_version(conn, existing_tables):
    if "import_metadata" not in existing_tables:
        return 0
    try:
        row = conn.execute(
            "SELECT value FROM import_metadata WHERE key = 'format_version'"
        ).fetchone()
        return int(row[0]) if row and str(row[0]).isdigit() else 0
    except Exception:
        return 0


def validate_v6_database(conn, existing_tables):
    required_v6_tables = {
        "agency",
        "routes",
        "stops",
        "calendar",
        "calendar_dates",
        "headsigns",
        "route_patterns",
        "route_pattern_stops",
        "timing_profiles",
        "pattern_schedules",
    }

    missing = required_v6_tables - existing_tables
    if missing:
        raise RuntimeError(
            "Missing required v6 tables: " + ", ".join(sorted(missing))
        )

    required_stop_cols = {
        "stop_idx",
        "stop_id",
        "stop_code",
        "stop_name",
        "stop_lat",
        "stop_lon",
        "parent_station",
        "stop_desc",
        "platform_code",
    }
    stops_cols = table_columns(conn, "stops")
    if not required_stop_cols.issubset(stops_cols):
        raise RuntimeError("The stops table is missing required v6 columns.")

    required_route_cols = {
        "route_idx",
        "route_id",
        "agency_id",
        "route_short_name",
        "route_long_name",
        "route_type",
        "route_desc",
    }
    routes_cols = table_columns(conn, "routes")
    if not required_route_cols.issubset(routes_cols):
        raise RuntimeError("The routes table is missing required v6 columns.")

    print("Detected v6 compact transit database. Ensuring indexes...")

    v6_indexes = [
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_stops_stop_id ON stops(stop_id)",
        "CREATE INDEX IF NOT EXISTS idx_stops_code ON stops(stop_code)",
        "CREATE INDEX IF NOT EXISTS idx_stops_parent ON stops(parent_station)",
        "CREATE INDEX IF NOT EXISTS idx_stops_lat_lon ON stops(stop_lat, stop_lon)",
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_routes_route_id ON routes(route_id)",
        "CREATE INDEX IF NOT EXISTS idx_routes_short_name ON routes(route_short_name)",
        "CREATE INDEX IF NOT EXISTS idx_patterns_route_idx ON route_patterns(route_idx)",
        "CREATE INDEX IF NOT EXISTS idx_rps_stop_idx ON route_pattern_stops(stop_idx, pattern_id)",
        "CREATE INDEX IF NOT EXISTS idx_calendar_dates_range ON calendar(start_date, end_date)",
    ]

    for statement in v6_indexes:
        conn.execute(statement)
    conn.commit()

    print("Validating v6 routing and schedule data...")

    pattern_count = conn.execute(
        "SELECT COUNT(*) FROM route_patterns"
    ).fetchone()[0]
    schedule_count = conn.execute(
        "SELECT COUNT(*) FROM pattern_schedules"
    ).fetchone()[0]
    profile_count = conn.execute(
        "SELECT COUNT(*) FROM timing_profiles"
    ).fetchone()[0]
    stop_count = conn.execute(
        "SELECT COUNT(*) FROM stops"
    ).fetchone()[0]
    route_count = conn.execute(
        "SELECT COUNT(*) FROM routes"
    ).fetchone()[0]
    total_trips = conn.execute(
        "SELECT COALESCE(SUM(trip_count), 0) FROM pattern_schedules"
    ).fetchone()[0]

    if pattern_count == 0 or schedule_count == 0:
        raise RuntimeError("No routing patterns or schedules found in v6 database.")

    missing_schedules = conn.execute("""
        SELECT COUNT(*)
        FROM route_patterns rp
        LEFT JOIN pattern_schedules ps
            ON ps.pattern_id = rp.pattern_id
        WHERE ps.pattern_id IS NULL
    """).fetchone()[0]
    if missing_schedules:
        raise RuntimeError(
            f"{missing_schedules:,} patterns have no schedule data."
        )

    missing_stops = conn.execute("""
        SELECT COUNT(*)
        FROM route_pattern_stops rps
        LEFT JOIN stops s
            ON s.stop_idx = rps.stop_idx
        WHERE s.stop_idx IS NULL
    """).fetchone()[0]
    if missing_stops:
        raise RuntimeError(
            f"{missing_stops:,} pattern stop entries reference missing stops."
        )

    print("")
    print("v6 routing validation completed.")
    print(f"Stops: {stop_count:,}")
    print(f"Routes: {route_count:,}")
    print(f"Patterns: {pattern_count:,}")
    print(f"Timing profiles: {profile_count:,}")
    print(f"Trips: {total_trips:,}")


def prepare_database(db_path):
    db_path = Path(db_path)

    if not db_path.exists():
        raise FileNotFoundError(f"Database not found: {db_path}")

    conn = sqlite3.connect(str(db_path))

    try:
        existing_tables = {
            row[0]
            for row in conn.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            )
        }

        format_version = get_format_version(conn, existing_tables)
        if format_version >= 6 or (
            "pattern_schedules" in existing_tables
            and "timing_profiles" in existing_tables
        ):
            validate_v6_database(conn, existing_tables)
            return

        required_tables = {
            "trips",
            "stop_times",
            "routes",
            "stops",
            "calendar",
        }

        missing = required_tables - existing_tables
        if missing:
            raise RuntimeError(
                "Missing required tables: " + ", ".join(sorted(missing))
            )

        trip_columns = table_columns(conn, "trips")
        stop_time_columns = table_columns(conn, "stop_times")
        stops_columns = table_columns(conn, "stops")

        required_trip_columns = {"trip_id", "route_id", "service_id"}
        required_stop_columns = {
            "trip_id",
            "stop_id",
            "stop_sequence",
            "arrival_time",
            "departure_time",
        }

        if not required_trip_columns.issubset(trip_columns):
            raise RuntimeError("The trips table is missing required columns.")

        if not required_stop_columns.issubset(stop_time_columns):
            raise RuntimeError(
                "The stop_times table is missing required columns."
            )

        direction_expression = (
            "COALESCE(t.direction_id, '')"
            if "direction_id" in trip_columns
            else "''"
        )

        print("Creating routing tables...")

        conn.execute("""
            CREATE TABLE IF NOT EXISTS route_patterns (
                pattern_id INTEGER PRIMARY KEY,
                route_id TEXT NOT NULL,
                direction_id TEXT NOT NULL,
                stop_count INTEGER NOT NULL
            )
        """)

        conn.execute("""
            CREATE TABLE IF NOT EXISTS route_pattern_stops (
                pattern_id INTEGER NOT NULL,
                stop_sequence INTEGER NOT NULL,
                stop_id TEXT NOT NULL,
                PRIMARY KEY (pattern_id, stop_sequence)
            )
        """)

        conn.execute("""
            CREATE TABLE IF NOT EXISTS trip_patterns (
                trip_id TEXT PRIMARY KEY,
                pattern_id INTEGER NOT NULL,
                service_id TEXT NOT NULL
            )
        """)

        # Rebuild only the derived tables.
        # Original GTFS tables and columns remain untouched.
        conn.execute("DELETE FROM route_pattern_stops")
        conn.execute("DELETE FROM trip_patterns")
        conn.execute("DELETE FROM route_patterns")
        conn.commit()

        print("Reading trips and their stop sequences...")

        query = f"""
            SELECT
                t.trip_id,
                t.route_id,
                {direction_expression} AS direction_id,
                t.service_id,
                st.stop_sequence,
                st.stop_id
            FROM trips t
            JOIN stop_times st
                ON st.trip_id = t.trip_id
            ORDER BY
                t.trip_id,
                st.stop_sequence
        """

        cursor = conn.execute(query)

        pattern_ids = {}
        pattern_count = 0
        trip_count = 0
        stop_count = 0

        pending_patterns = []
        pending_pattern_stops = []
        pending_trip_patterns = []

        current_trip_id = None
        current_route_id = None
        current_direction_id = None
        current_service_id = None
        current_stops = []

        def save_current_trip():
            nonlocal pattern_count
            nonlocal trip_count
            nonlocal stop_count

            if current_trip_id is None or not current_stops:
                return

            key = (
                current_route_id,
                current_direction_id,
                tuple(current_stops),
            )

            pattern_id = pattern_ids.get(key)

            if pattern_id is None:
                pattern_count += 1
                pattern_id = pattern_count
                pattern_ids[key] = pattern_id

                pending_patterns.append((
                    pattern_id,
                    current_route_id,
                    current_direction_id,
                    len(current_stops),
                ))

                for sequence, stop_id in enumerate(current_stops, start=1):
                    pending_pattern_stops.append((
                        pattern_id,
                        sequence,
                        stop_id,
                    ))

            pending_trip_patterns.append((
                current_trip_id,
                pattern_id,
                current_service_id,
            ))

            trip_count += 1
            stop_count += len(current_stops)

        for row in cursor:
            (
                trip_id,
                route_id,
                direction_id,
                service_id,
                stop_sequence,
                stop_id,
            ) = row

            if trip_id != current_trip_id:
                save_current_trip()

                current_trip_id = trip_id
                current_route_id = route_id
                current_direction_id = direction_id
                current_service_id = service_id
                current_stops = []

            current_stops.append(stop_id)

            if len(pending_trip_patterns) >= 5000:
                conn.executemany(
                    """
                    INSERT INTO route_patterns
                    (pattern_id, route_id, direction_id, stop_count)
                    VALUES (?, ?, ?, ?)
                    """,
                    pending_patterns,
                )

                conn.executemany(
                    """
                    INSERT INTO route_pattern_stops
                    (pattern_id, stop_sequence, stop_id)
                    VALUES (?, ?, ?)
                    """,
                    pending_pattern_stops,
                )

                conn.executemany(
                    """
                    INSERT INTO trip_patterns
                    (trip_id, pattern_id, service_id)
                    VALUES (?, ?, ?)
                    """,
                    pending_trip_patterns,
                )

                conn.commit()

                pending_patterns.clear()
                pending_pattern_stops.clear()
                pending_trip_patterns.clear()

                print(
                    f"Processed {trip_count:,} trips; "
                    f"identified {pattern_count:,} patterns."
                )

        save_current_trip()

        if pending_patterns:
            conn.executemany(
                """
                INSERT INTO route_patterns
                (pattern_id, route_id, direction_id, stop_count)
                VALUES (?, ?, ?, ?)
                """,
                pending_patterns,
            )

        if pending_pattern_stops:
            conn.executemany(
                """
                INSERT INTO route_pattern_stops
                (pattern_id, stop_sequence, stop_id)
                VALUES (?, ?, ?)
                """,
                pending_pattern_stops,
            )

        if pending_trip_patterns:
            conn.executemany(
                """
                INSERT INTO trip_patterns
                (trip_id, pattern_id, service_id)
                VALUES (?, ?, ?)
                """,
                pending_trip_patterns,
            )

        conn.commit()

        print("Creating indexes...")

        indexes = [
            """
            CREATE INDEX IF NOT EXISTS idx_trip_patterns_pattern
            ON trip_patterns(pattern_id)
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_trip_patterns_service
            ON trip_patterns(service_id)
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_pattern_stops_stop
            ON route_pattern_stops(stop_id)
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_patterns_route
            ON route_patterns(route_id, direction_id)
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_stop_times_stop_departure
            ON stop_times(stop_id, departure_time, trip_id)
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_stop_times_trip_sequence
            ON stop_times(trip_id, stop_sequence)
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_trips_service
            ON trips(service_id)
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_calendar_service
            ON calendar(service_id)
            """,
        ]

        if {"stop_lat", "stop_lon"}.issubset(stops_columns):
            indexes.append("""
                CREATE INDEX IF NOT EXISTS idx_stops_lat_lon
                ON stops(stop_lat, stop_lon)
            """)

        if "stop_code" in stops_columns:
            indexes.append("""
                CREATE INDEX IF NOT EXISTS idx_stops_code
                ON stops(stop_code)
            """)

        for statement in indexes:
            conn.execute(statement)

        conn.commit()

        print("Validating derived routing data...")

        pattern_count_db = conn.execute(
            "SELECT COUNT(*) FROM route_patterns"
        ).fetchone()[0]

        trip_count_db = conn.execute(
            "SELECT COUNT(*) FROM trip_patterns"
        ).fetchone()[0]

        pattern_stop_count = conn.execute(
            "SELECT COUNT(*) FROM route_pattern_stops"
        ).fetchone()[0]

        missing_trips = conn.execute("""
            SELECT COUNT(*)
            FROM trips t
            LEFT JOIN trip_patterns tp
                ON tp.trip_id = t.trip_id
            WHERE tp.trip_id IS NULL
        """).fetchone()[0]

        if missing_trips:
            raise RuntimeError(
                f"{missing_trips:,} trips have no routing pattern."
            )

        print("")
        print("Routing preparation completed.")
        print(f"Patterns: {pattern_count_db:,}")
        print(f"Trips: {trip_count_db:,}")
        print(f"Pattern stops: {pattern_stop_count:,}")
        print(f"Trips without patterns: {missing_trips:,}")

    finally:
        conn.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--database",
        required=True,
        help="Path to the SQLite transit database",
    )

    args = parser.parse_args()
    prepare_database(args.database)
