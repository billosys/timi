(ns timi.datasource.sqlite.bootstrap
  (:require
    [timi.domain.query-handler :as queries]
    [timi.domain.user :as user-repo]
    [timi.datasource.sqlite.entry-repo :as sqlite-entry-repo]
    [timi.datasource.sqlite.task-repo :as sqlite-task-repo]
    [timi.datasource.sqlite.project-repo :as sqlite-project-repo]
    [timi.datasource.sqlite.queries :as sqlite-queries]
    [timi.application.timi-identity :as identity]
    [timi.domain.project :as project]
    [timi.domain.task :as task]
    [timi.domain.entry :as entry]))

(def user-repo
  (reify
    user-repo/UserRepository
    (-user-exists? [_ _] true)
    identity/Identity
    (-user-id-for-username [_ _] 1)))

(defmacro with-sqlite [db-spec & body]
  `(let [db# ~db-spec]
     (entry/with-impl (sqlite-entry-repo/new db#)
       (project/with-repo-impl (sqlite-project-repo/new db#)
         (task/with-impl (sqlite-task-repo/new db#)
           (user-repo/with-impl user-repo
             (identity/with-impl user-repo
               (queries/with-handler (sqlite-queries/handler db#)
                 ~@body))))))))