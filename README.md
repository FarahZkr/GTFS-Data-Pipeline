# STM - GTFS Data Pipeline
Takes raw GTFS-Realtime feeds and enriches them with static schedule details.

**Live demo:** https://stm-gtfs-service-568483343462.northamerica-northeast1.run.app/

This is a small Java app that downloads Montréal's bus schedule from the STM, checks it for problems, and shows live bus positions on a map. It runs on Google Cloud Run.

I built it to learn how transit data actually works. Almost every transit agency publishes its data in the same format, using GTFS, and I wanted to understand how the data was being handled and communicated to various modern applications. I also wanted to experiment by deploying something real to the cloud and see how it functions.

> On the first load, it may be slow as it runs on a free-tier setup that shuts down when nobody is using it, but wakes up after the first visit to load the schedule.

## What it does

- Downloads the STM's GTFS schedule retrieved from the web (a zip of CSV files) and reads it in the background.
- Gives a report on what it found: how many stops, routes, trips and shapes there are, which files were found or missing, and a few simple checks on the data (like [trips that point to a route that doesn't exist] and [stops with coordinates outside the Montréal area]).
- Pulls live bus positions from the STM's real-time feed (GTFS-RT).
- Lets you click anywhere on the map to see the stops within 500 metres. I used an R-tree for this so the search stays fast with thousands of stops.
- Shows everything on a Leaflet map: route lines, live buses, and stops.

## How it works

- **Java 17 + Spring Boot** for the backend.
- Loading the schedule takes a while. When the page opens, it asks the server to load it (/api/load), then checks /api/status every second until it says READY or FAILED. If the data was already loaded in the last 12 hours, the server skips the download, so visitors don't trigger it over and over.
- The live positions come from the STM's real-time API, using my own API key. The server keeps the last result for a few seconds, so many visitors share one call to the STM instead of each making their own.
- The map gets its data from two GeoJSON endpoints: /api/map/routes for the route lines and /api/map/vehicles for the live buses.

## Why I made some of the choices I did

**Secrets are not in the code.** My STM API key and Mapbox token live in Google Secret Manager. `application.properties` only has placeholders like `${STM_API_KEY}`, so the file is safe to publish. The app runs as a service account that can read only those secrets and nothing else. I wanted to practice giving something the least access it needs.

**Cloud Run.** It only runs (and only costs anything) when someone is using it, and it scales down to zero. I also capped the number of instances so a burst of traffic can't run up a bill. This is a portfolio project, so I didn't want to pay for it to sit idle.

**No frontend framework.** I wanted the backend and the data to be the interesting part, so the map page is kept simple.

## What went wrong, and what I learned

This is the part I learned the most from. The app worked on my laptop and failed on Cloud Run, several times, for different reasons.

1. **"Container failed to start."** The error message is the same for almost every startup problem, so it told me nothing. The real reason was in the logs: Spring couldn't find a property. My keys only existed in my IDE's run settings, not in the app's config. Lesson: what works in my IDE isn't what gets deployed.

2. **Reading logs from the terminal.** The web console kept opening with the wrong Google account, so I learned to use `gcloud logging read` instead. It was faster anyway.

3. **Secret names can't have dots.** `mapbox.access.token` was rejected by Secret Manager, so I used names like `MAPBOX_ACCESS_TOKEN` instead. Spring Boot can match those environment variables to dotted property names.

4. **`.gitignore` affects the build.** `gcloud builds submit` skips files listed in `.gitignore`. Since my properties file only contains placeholders, I stopped ignoring it.

5. **The ingestion kept failing with a file-not-found error, and my code was hiding the real reason.** The report only said "error while parsing zip file". Once I logged the actual exception, I saw it was trying to download from a URL I never wrote in my code. It turned out there was an old `GTFS_SCHEDULE_URL` environment variable left on the Cloud Run service from earlier. In Spring Boot, an environment variable beats the value in `application.properties`. That's why it worked locally and not in the cloud. Removing it fixed it. Lessons: log the real exception, and check what's actually set on the deployed service instead of trusting what's in the file.

## Run it locally

You need Java 17 and your own API keys.

1. Get an STM API key and a Mapbox token.
2. Set them as environment variables:
   ```
   export STM_API_KEY=your_key
   export MAPBOX_ACCESS_TOKEN=your_token
   ```
3. Run:
   ```
   ./mvnw spring-boot:run
   ```
4. Open `http://localhost:8080`.

## Deploying

I build the image with Cloud Build and deploy to Cloud Run, with the two secrets attached as environment variables:

```
gcloud builds submit --tag REGION-docker.pkg.dev/PROJECT/REPO/gtfs-service:TAG .

gcloud run deploy stm-gtfs-service \
  --image=REGION-docker.pkg.dev/PROJECT/REPO/gtfs-service:TAG \
  --region=REGION \
  --max-instances=2 \
  --set-secrets=STM_API_KEY=STM_API_KEY:latest,MAPBOX_ACCESS_TOKEN=MAPBOX_ACCESS_TOKEN:latest
```

## What I'd do next

- [ ] Make the agency configurable so it also works with STL (Laval), since GTFS is a standard format.

## Data and attribution

The schedule and real-time data come from the Société de transport de Montréal (STM) open data, used under the Creative Commons Attribution 4.0 license (CC BY 4.0). Source: Société de transport de Montréal.

The app only reads stops, routes, trips and shapes. It never reads schedule times, so metro lines and stations are only drawn on the map for reference. The app doesn't use any metro timetables.

This is a personal learning project. It isn't affiliated with or endorsed by the STM, and the data is provided as is, so it may be wrong or out of date.
