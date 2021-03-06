package by.bsu.utils

import by.bsu.model.dao._
import by.bsu.model.repository._
import by.bsu.utils.RouteService._
import by.bsu.web.api.UpdatingDataController
import org.apache.log4j.Logger

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Future}
import scala.language.postfixOps
import scala.util.Try

class FilmsService(filmsDAO: FilmsDAO) {

  val updateDataController = new UpdatingDataController

  var LOGGER: Logger = Logger.getLogger(this.getClass.getName)

  private val actorsFilmsDAO = new ActorsFilmsDAO(filmsDAO.config)
  private val countriesFilmsDAO = new CountriesFilmsDAO(filmsDAO.config)
  private val directorsFilmsDAO = new DirectorsFilmsDAO(filmsDAO.config)
  private val genresFilmsDAO = new GenresFilmsDAO(filmsDAO.config)

  def getAllPublic = {
    LOGGER.trace(s"Getting all public films")
    filmsDAO.findAll(true).flatMap(getFullFilm)
  }

  def getAllPublicPagination(limit: Int, offset: Int) = {
    LOGGER.trace(s"Getting all public films with pagination (limit: $limit, offset: $offset)")
    filmsDAO.findAllPagination(limit, offset, true).flatMap(getFullFilm)
  }

  def getAllPrivatePagination(limit: Int, offset: Int) = {
    LOGGER.trace(s"Getting all private films with pagination (limit: $limit, offset: $offset)")
    filmsDAO.findAllPagination(limit, offset, false).flatMap(getFullFilm)
  }

  def getAllPrivate = {
    LOGGER.trace(s"Getting all private films")
    filmsDAO.findAll(false).flatMap(getFullFilm)
  }

  def getAll: Future[Seq[Film]] = {
    LOGGER.trace(s"Getting all films")
    filmsDAO.findAll()
  }

  def getById(id: Int) = {
    LOGGER.trace(s"Getting film with $id id")
    filmsDAO.findById(id).map(_.map(Seq(_))).map(_.getOrElse(Seq.empty))
  }

  def getAllFullFilms: Future[Seq[NewFilmWithFieldsId]] = {
    LOGGER.debug("Getting all films")
    val films = filmsDAO.findAllWithLanguage()
    films.map(films => LOGGER.debug(s"${films.size} films were gotten"))
    val actorsToFilm = actorsFilmsDAO.joinActorsToFilmsId().map(_.map(data => data._1 -> data._2.map(_._2)))
    val countriesToFilm = countriesFilmsDAO.joinCountriesToFilmsId().map(_.map(data => data._1 -> data._2.map(_._2)))
    val directorsToFilm = directorsFilmsDAO.joinDirectorsToFilmsId().map(_.map(data => data._1 -> data._2.map(_._2)))
    val genresToFilm = genresFilmsDAO.joinGenresToFilmsId().map(_.map(data => data._1 -> data._2.map(_._2)))

    for {
      filmsFut <- films
      actorsToFilmFut <- actorsToFilm
      countriesToFilmFut <- countriesToFilm
      directorsToFilmFut <- directorsToFilm
      genresToFilmFut <- genresToFilm

    } yield filmsFut.map(data => NewFilmWithFieldsId(data._1.id, data._1.name, data._1.ageLimit,
      actorsToFilmFut.get(data._1.id.get).map(_.filter(_.nonEmpty).map(_.get).map(data => data.id.get -> data.name)),
      genresToFilmFut.get(data._1.id.get).map(_.filter(_.nonEmpty).map(_.get).map(data => data.id.get -> data.name)),
      countriesToFilmFut.get(data._1.id.get).map(_.filter(_.nonEmpty).map(_.get).map(data => data.id.get -> data.name)),
      directorsToFilmFut.get(data._1.id.get).map(_.filter(_.nonEmpty).map(_.get).map(data => data.id.get -> data.name)),
      data._1.shortDescription, data._1.timing, data._1.image, data._1.releaseDate, data._1.awards, data._2.map(data => data.id.get -> data.name), data._1.isPublic))

  }

  def getFullByName(name: String): Future[Seq[NewFilmWithFieldsId]] = {
    LOGGER.debug(s"Getting all films by name: $name")
    val films: Future[Seq[(Film, Option[Language])]] = filmsDAO.findAllByName(name)
    films.map(films => LOGGER.debug(s"${films.size} films were gotten"))
    films.flatMap(getFullFilm)
  }

  def getFullByDate(releaseDate: String): Future[Seq[NewFilmWithFieldsId]] = {
    LOGGER.debug(s"Getting all films by name: $releaseDate")
    val films = filmsDAO.findAllByYear(releaseDate)
    films.map(films => LOGGER.debug(s"${films.size} films were gotten"))
    films.flatMap(getFullFilm)
  }

  def getFullFilmsByDirector(directorName: String): Future[Option[Seq[Seq[NewFilmWithFieldsId]]]] = {
    val director = directorsService.getByName(directorName)
    val filmIds = director.flatMap(info => HelpFunctions.fOption(info.map(data => directorsFilmsService.getByDirectorId(data.id.get).map(_.map(_.filmId)))))
    val films = filmIds.flatMap(data => HelpFunctions.fOption(data.map(info => Future.sequence(info.map(data => filmsDAO.findAllById(data))))))
    films.flatMap(info => HelpFunctions.fOption(info.map(data => Future.sequence(data.map(getFullFilm)))))
  }

  def getFullByDirectorNameDate(directorName: String, filmName: String, releaseDate: String): Future[Seq[NewFilmWithFieldsId]] = {
    val directors = getFullFilmsByDirector(directorName).map(_.map(_.flatten))

    val films = filmsDAO.findAllByNameDate(filmName, releaseDate).map(getFullFilm).flatten

    val results = for {
      dirFut <- directors
      filmsFut <- films
    } yield (dirFut.map(_.intersect(filmsFut)))

    results.map(data => data.filter(_.nonEmpty).getOrElse(Seq.empty[NewFilmWithFieldsId]))
  }

  def getFullByDirectorName(directorName: String, filmName: String) = {
    val directors = getFullFilmsByDirector(directorName).map(_.map(_.flatten))

    val films = filmsDAO.findAllByName(filmName).map(getFullFilm).flatten

    val results = for {
      dirFut <- directors
      filmsFut <- films
    } yield (dirFut.map(_.intersect(filmsFut)))

    results.map(data => data.filter(_.nonEmpty).getOrElse(Seq.empty[NewFilmWithFieldsId]))
  }

  def getFullByDirectorDate(directorName: String, date: String) = {
    val directors = getFullFilmsByDirector(directorName).map(_.map(_.flatten))

    val films = filmsDAO.findAllByDate(date).map(getFullFilm).flatten

    val results = for {
      dirFut <- directors
      filmsFut <- films
    } yield (dirFut.map(_.intersect(filmsFut)))

    results.map(data => data.filter(_.nonEmpty).getOrElse(Seq.empty[NewFilmWithFieldsId]))
  }

  def getFullByNameDate(filmName: String, releaseDate: String): Future[Seq[NewFilmWithFieldsId]] = {
    filmsDAO.findAllByNameDate(filmName, releaseDate).map(getFullFilm).flatten
  }

  private def getFullFilm(films: Seq[(Film, Option[Language])]): Future[Seq[NewFilmWithFieldsId]] = {

    val actorsToFilm = actorsFilmsDAO.joinActorsToFilmsId().map(_.map(data => data._1 -> data._2.map(_._2)))
    val countriesToFilm = countriesFilmsDAO.joinCountriesToFilmsId().map(_.map(data => data._1 -> data._2.map(_._2)))
    val directorsToFilm = directorsFilmsDAO.joinDirectorsToFilmsId().map(_.map(data => data._1 -> data._2.map(_._2)))
    val genresToFilm = genresFilmsDAO.joinGenresToFilmsId().map(_.map(data => data._1 -> data._2.map(_._2)))
    for {
      actorsToFilmFut <- actorsToFilm
      countriesToFilmFut <- countriesToFilm
      directorsToFilmFut <- directorsToFilm
      genresToFilmFut <- genresToFilm

    } yield films.map(data => NewFilmWithFieldsId(data._1.id, data._1.name, data._1.ageLimit,
      actorsToFilmFut.get(data._1.id.get).map(_.filter(_.nonEmpty).map(_.get).map(data => data.id.get -> data.name)),
      genresToFilmFut.get(data._1.id.get).map(_.filter(_.nonEmpty).map(_.get).map(data => data.id.get -> data.name)),
      countriesToFilmFut.get(data._1.id.get).map(_.filter(_.nonEmpty).map(_.get).map(data => data.id.get -> data.name)),
      directorsToFilmFut.get(data._1.id.get).map(_.filter(_.nonEmpty).map(_.get).map(data => data.id.get -> data.name)),
      data._1.shortDescription, data._1.timing, data._1.image, data._1.releaseDate, data._1.awards, data._2.map(data => data.id.get -> data.name), data._1.isPublic))

  }

  def updateById(id: Int, film: Film): Future[Int] = {
    LOGGER.trace(s"Updating film with $id id")
    filmsDAO.update(id, film)
  }

  def deleteById(id: Int): Future[Boolean] = {
    LOGGER.trace(s"Deleting film with $id")
    filmsDAO.deleteById(id)
  }

  def makePublic(id: Int): Future[Int] = {
    LOGGER.trace(s"Making film $id id public")
    filmsDAO.changeVisibility(id, isVisible = true)
  }

  def makePrivate(id: Int): Future[Int] = {
    LOGGER.trace(s"Making film $id id private")
    filmsDAO.changeVisibility(id, false)
  }

  def createWithoutFilling(film: NewFilmWithId): Future[NewFilmWithId] = {
    LOGGER.debug(s"Inserting film ${film.name}")

    val actorsId = 0
    val genresId = 1
    val directorsId = 2
    val countriesId = 3


    val result = filmsDAO.insert(Film(film.id, film.name, film.ageLimit, film.shortDescription, film.timing, film.image,
      film.releaseDate, film.awards, film.languageId, Option(false))).map(_.get)

    for {
      resultFut <- result
      insertionFut <- insertLinkedTables(resultFut.id, film).map(data => film.copy(actorsId = data(actorsId), genresId = data(genresId), directorsId = data(directorsId), countriesId = data(countriesId)))


    } yield (insertionFut.copy(id = resultFut.id, name = resultFut.name, ageLimit = resultFut.ageLimit, shortDescription = resultFut.shortDescription,
      timing = resultFut.timing, releaseDate = resultFut.releaseDate, image = resultFut.image))

  }

  private def insertLinkedTables(id: Option[Int], film: NewFilmWithId): Future[List[Option[Seq[Int]]]] = {
    val actorsListForInsertion = HelpFunctions.fOption(film.actorsId.map(_.map(data =>
      ActorFilm(None, data, id.get))).map(actorsFilmsDAO.insertListActorFilm))
      .map(_.flatten).map(_.map(_.map(_.actorId)))

    val genresListForInsertion = HelpFunctions.fOption(film.genresId.map(_.map(data =>
      GenreFilm(None, data, id.get))).map(genresFilmsDAO.insertListGenresFilm))
      .map(_.flatten).map(_.map(_.map(_.genreId)))

    val directorsListForInsertion = HelpFunctions.fOption(film.directorsId.map(_.map(data =>
      DirectorFilm(None, data, id.get))).map(directorsFilmsDAO.insertListDirectorFilm))
      .map(_.flatten).map(_.map(_.map(_.directorId)))

    val countriesListForInsertion = HelpFunctions.fOption(film.countriesId.map(_.map(data =>
      CountryFilm(None, data, id.get))).map(countriesFilmsDAO.insertListCountryFilm))
      .map(_.flatten).map(_.map(_.map(_.countryId)))

    Future.sequence(List(actorsListForInsertion, genresListForInsertion, directorsListForInsertion, countriesListForInsertion))
  }

  private def deleteByIdLinkedTablesQuery(id: Int) = {
    (for {
      genresDeleted <- genresFilmsDAO.deleteByFilmIdQuery(id).asTry
      directorsDeleted <- directorsFilmsDAO.deleteByFilmIdQuery(id).asTry
      countriesDeleted <- countriesFilmsDAO.deleteByFilmIdQuery(id).asTry
      actorsDeleted <- actorsFilmsDAO.deleteByFilmIdQuery(id)
      filmsDeleted <- filmsDAO.deleteByFilmIdQuery(id)
    } yield (filmsDeleted))
  }

  def createFilmWithFilling(newFilm: NewFilmWithFields) = {
    LOGGER.debug(s"Creating film without filling fields of ${newFilm.name} of ${newFilm.releaseDate}")

    val result = HelpFunctions.fOption(Try(getFilmByNameAndYear(newFilm.name, newFilm.releaseDate.toInt)).toOption)
    result.map(_.getOrElse(Seq.empty[NewFilmWithFields]))
  }

  def replaceEmptyFilm(newFilm: NewFilmWithFields, filmData: Future[NewFilmWithFields]): Future[NewFilmWithFields] = {

    LOGGER.trace(s"Replacing empty fields of film")

    val genresFilled = if (newFilm.genres.isEmpty) filmData.map(_.genres) else Future(newFilm.genres)
    val actorsFilled = if (newFilm.actors.isEmpty) filmData.map(_.actors) else Future(newFilm.actors)
    val countriesFilled = if (newFilm.countries.isEmpty) filmData.map(_.countries) else Future(newFilm.countries)
    val directorsFilled = if (newFilm.directors.isEmpty) filmData.map(_.directors) else Future(newFilm.directors)
    val languageFilled = if (newFilm.languageName.isEmpty) filmData.map(_.languageName) else Future(newFilm.languageName)
    val descriptionFilled = if (newFilm.directors.isEmpty) filmData.map(_.shortDescription) else Future(newFilm.shortDescription)
    val awardsFilled = if (newFilm.awards.isEmpty) filmData.map(_.awards) else Future(newFilm.awards)
    val timingFilled = if (newFilm.timing.isEmpty) filmData.map(_.timing) else Future(newFilm.timing)
    val imageFilled = if (newFilm.image.isEmpty) filmData.map(_.image) else Future(newFilm.image)
    val ageLimitFilled = if (newFilm.ageLimit.isEmpty) filmData.map(_.ageLimit) else Future(newFilm.ageLimit)

    for {
      genresFilledFut <- genresFilled
      actorsFilledFut <- actorsFilled
      countriesFilledFut <- countriesFilled
      directorsFilledFut <- directorsFilled
      languageFilledFut <- languageFilled
      descriptionFilledFut <- descriptionFilled
      awardsFilledFut <- awardsFilled
      timingFilledFut <- timingFilled
      imageFilledFut <- imageFilled
      ageLimitFilledFut <- ageLimitFilled
    } yield (NewFilmWithFields(None, newFilm.name, ageLimitFilledFut, actorsFilledFut, genresFilledFut, countriesFilledFut,
      directorsFilledFut, descriptionFilledFut, timingFilledFut, imageFilledFut,
      newFilm.releaseDate, awardsFilledFut, languageFilledFut, Option(false)))
  }

  def getFilmByNameAndYear(filmName: String, year: Int) = {

    LOGGER.trace(s"Trying to more info about film $filmName - $year")
    val data = updateDataController.getAdditionalDataFromApi(filmName, year)
    val result = data.map(_.map(film => NewFilmWithFields(None, film.Title, Option(film.Rated),
      Option(film.Actors.split(",").toSeq.map(_.trim)), Option(film.Genre.split(",").toSeq.map(_.trim)), Option(film.Country.split(",").toSeq.map(_.trim)), Option(film.Director.split(",").toSeq.map(_.trim)),
      Option(film.Plot), Option(film.Runtime), Option(film.Poster), film.Released, Option(film.Awards), Option(film.Language), Option(false))))

    (result)
  }

  def updateFilmsPerDay(): Future[List[NewFilmWithId]] = {
    LOGGER.trace(s"Trying to load genres from API")
    val data = updateDataController.periodicUpdateData()
    data._1.map(_.map(name => NewFilmWithId(None, name, None, None, None, None, None, None, None, None, data._2, None, None, Option(false))))
  }


  def insertFilmWithFields(film: NewFilmWithFields): Future[NewFilmWithId] = {
    val creationOfActors = HelpFunctions.fOption(film.actors.map(info => Future.sequence(info.map(name => Actor(None, name)).map(actorsService.create))).map(_.map(_.map(_.get.id.get))))
    val creationOfGenres = HelpFunctions.fOption(film.genres.map(info => Future.sequence(info.map(name => Genre(None, name)).map(genresService.create))).map(_.map(_.map(_.get.id.get))))
    val creationOfCountries = HelpFunctions.fOption(film.countries.map(info => Future.sequence(info.map(name => Country(None, name)).map(countriesService.create))).map(_.map(_.map(_.get.id.get))))
    val creationOfDirectors = HelpFunctions.fOption(film.directors.map(info => Future.sequence(info.map(name => Director(None, name)).map(directorsService.create))).map(_.map(_.map(_.get.id.get))))
    val creationOfLanguages = languagesService.create(Language(None, film.languageName.get)).map(_.map(_.id.get))

    val creationOfFilms = for {

      creationOfActorsFut <- creationOfActors
      creationOfGenresFut <- creationOfGenres
      creationOfCountriesFut <- creationOfCountries
      creationOfDirectorsFut <- creationOfDirectors
      creationOfLanguagesFut <- creationOfLanguages

    } yield (createWithoutFilling(NewFilmWithId(None, film.name, film.ageLimit, creationOfActorsFut,
      creationOfGenresFut, creationOfCountriesFut, creationOfDirectorsFut, film.shortDescription,
      film.timing, film.image, film.releaseDate, film.awards, creationOfLanguagesFut, Option(false))))


    creationOfFilms.flatten
  }


  def getRecommend(id: Int) = {
    val topInGenresId = HelpFunctions.fOption(Try(genresFilmsService.getByName(id).map(data => HelpFunctions.fOption(data.map(genresService.getTopFilmsFromGenre)))
      .flatten.map(_.get).map(_.map(data => (data._1.get)))).toOption)
    val listOfFilms = filmsDAO.getRecommendedFilms(id).map(_.map(_._1))

    val recommendedIds = for {
      topInGenresIdFuture <- topInGenresId
      listOfFilmsFuture <- listOfFilms
    } yield (if (topInGenresIdFuture.nonEmpty) {
      topInGenresIdFuture.get ++ listOfFilmsFuture
    } else listOfFilmsFuture)

    val result = recommendedIds.map(_.distinct.filter(_ != id)).map(_.map(getById)).map(Future.sequence(_)).flatten
    result.map(_.take(5))
  }
}
