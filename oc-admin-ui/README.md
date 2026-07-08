# OpenCrawling Admin UI

Modern SPA for OpenCrawling built with React 19, Vite, and Tailwind CSS.

## Tech Stack
- **Framework**: React 19.2
- **Build Tool**: Vite 6
- **Styling**: Tailwind CSS (Enterprise Dark Mode)
- **Icons**: Lucide React
- **Forms**: React Hook Form
- **Charts**: Recharts
- **API Client**: Axios

## Development
1. Install dependencies: `npm install`
2. Start dev server: `npm run dev` (proxies `/api` to `localhost:8080`)

## Build & Integration
The project is configured as a Maven module. Running `mvn clean install` on the parent project will:
1. Build the React application using `npm run build`.
2. Package the static assets into the JAR under `META-INF/resources`.

### Spring Boot Serving
Spring Boot automatically serves content from `META-INF/resources`. 
To handle SPA routing (where the browser handles URL changes), ensure you have a Controller or Configuration to forward non-API requests to `index.html`.

Example `WebConfig.java`:
```java
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Forward all non-api routes to index.html for SPA routing
        registry.addViewController("/{path:[^\\.]*}")
                .setViewName("forward:/index.html");
    }
}
```

### API Proxy
The Vite configuration includes a proxy for `/api` and `/ws` to `localhost:8080` during development.
In production, the UI will be served from the same origin as the API.
