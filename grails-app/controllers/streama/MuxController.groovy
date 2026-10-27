package streama

import grails.converters.JSON
import grails.transaction.Transactional

import static org.springframework.http.HttpStatus.*

/**
 * Mux helpers for the admin UI: whether Mux is configured at all (the first-run gate
 * depends on it) and the ingest status of an uploaded video while the UI polls.
 */
class MuxController {

  def muxService

  static responseFormats = ['json']

  def configured() {
    render([configured: muxService.isConfigured()] as JSON)
  }

  @Transactional
  def fileStatus() {
    def file = File.get(params.getInt('id'))

    if (!file) {
      render status: NOT_FOUND
      return
    }

    muxService.resolveAsset(file)
    render(file.getSimpleInstance() as JSON)
  }
}
