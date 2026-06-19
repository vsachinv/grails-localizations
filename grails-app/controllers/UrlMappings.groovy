class UrlMappings {

    static mappings = {

        // ── Standard HTML routes (default Grails convention) ──────────────────
        "/$controller/$action?/$id?(.$format)?"()

        "/"(view: "/index")
        "500"(view: '/error')
        "404"(view: '/notFound')

        // ── REST API ──────────────────────────────────────────────────────────
        // Generates:
        //   GET    /api/localizations           → index
        //   POST   /api/localizations           → save
        //   GET    /api/localizations/{id}      → show
        //   PUT    /api/localizations/{id}      → update
        //   PATCH  /api/localizations/{id}      → update
        //   DELETE /api/localizations/{id}      → delete
        "/api/localizations"(resources: 'localization') {

            // GET  /api/localizations/search?q=...&locale=...
            "/search"(controller: 'localization', action: 'search')

            // GET  /api/localizations/cache
            "/cache"(controller: 'localization', action: 'cache', method: 'GET')

            // POST /api/localizations/cache/reset
            "/cache/reset"(controller: 'localization', action: 'reset', method: 'POST')
        }
    }
}
