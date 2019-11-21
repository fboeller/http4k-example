import org.http4k.client.ApacheClient
import org.http4k.contract.ContractRoute
import org.http4k.contract.contract
import org.http4k.contract.meta
import org.http4k.contract.openapi.ApiInfo
import org.http4k.contract.openapi.v3.OpenApi3
import org.http4k.core.*
import org.http4k.core.Method.*
import org.http4k.core.Status.Companion.NO_CONTENT
import org.http4k.core.Status.Companion.OK
import org.http4k.core.body.form
import org.http4k.filter.DebuggingFilters
import org.http4k.format.Jackson
import org.http4k.format.Jackson.auto
import org.http4k.routing.*
import org.http4k.server.Jetty
import org.http4k.server.asServer
import org.http4k.template.HandlebarsTemplates
import org.http4k.template.ViewModel
import org.http4k.template.viewModel

data class Person(val name: String)
val personLens = Body.auto<Person>().toLens()
val personsLens = Body.auto<List<Person>>("A list of persons").toLens()

val persons: MutableList<Person> = mutableListOf()

fun apiServer(): RoutingHttpHandler {
    val getPersonsEndpoint = { _: Request ->
        Response(OK).with(personsLens of persons)
    }
    val putPersonEndpoint = { request: Request ->
        persons.add(personLens(request))
        Response(NO_CONTENT)
    }

    return routes(
        "/persons" bind GET to getPersonsEndpoint,
        "/persons" bind PUT to putPersonEndpoint
    )
}

data class Persons(val persons: List<Person>) : ViewModel

fun uiServer(): RoutingHttpHandler {
    val renderer = HandlebarsTemplates().HotReload("src/main/resources")
    val viewLens = Body.viewModel(renderer, ContentType.TEXT_HTML).toLens()

    val getPersonViewEndpoint = { _: Request ->
        Response(OK).with(viewLens of Persons(persons))
    }
    val postPersonViewEndpoint = { request: Request ->
        request.form("name")?.let { persons.add(Person(it)) }
        getPersonViewEndpoint(request)
    }

    return routes(
        "/persons" bind GET to getPersonViewEndpoint,
        "/persons" bind POST to postPersonViewEndpoint,
        "/css" bind static(ResourceLoader.Directory("src/main/resources/css"))
    )
}

fun echoServer(): ContractRoute {

    // this specifies the route contract, including examples of the input and output body objects - they will
    // get exploded into JSON schema in the OpenAPI docs
    val spec = "/echo" meta {
        summary = "echoes the name"
        receiving(personsLens to listOf(Person("Jim")))
        returning(OK, personsLens to listOf(Person("Jim")))
    } bindContract POST

    val echo: HttpHandler = { request: Request ->
        Response(NO_CONTENT)
        // val received = personLens(request)
        // Response(OK).with(personLens of received)
    }

    return spec to echo
}

fun server(): RoutingHttpHandler {
    val contract = contract {
        renderer = OpenApi3(ApiInfo("My great API", "v1.0"), Jackson)
        descriptionPath = "/swagger.json"
        routes += echoServer()
    }

    return DebuggingFilters.PrintRequestAndResponse().then(
        routes(
            "/api" bind apiServer(),
            "/ui" bind uiServer(),
            "/contract" bind contract
        )
    )
}

fun testCalls() {
    val client = ApacheClient()

    client(Request(PUT, "http://localhost:9000/api/persons").let { personLens(Person("John Doe"), it) })
    client(Request(PUT, "http://localhost:9000/api/persons").let { personLens(Person("Gustav"), it) })

    // client(Request(GET, "http://localhost:9000/api/persons"))
    // client(Request(GET, "http://localhost:9000/ui/persons"))
    // client(Request(POST, "http://localhost:9000/contract/echo").let { personLens(Person("Friedrich"), it) })
    client(Request(POST, "http://localhost:9000/contract/echo")
        .body("""[{ "name": "Heinz", "abc": "cde" }]"""))
    // client(Request(GET, "http://localhost:9000/contract/swagger.json"))
}

fun main() {
    val jettyServer = server()
        .asServer(Jetty(9000))
        .start()

    testCalls()

    jettyServer.block()
    jettyServer.stop()
}