
# Gem Prototype

This is the initial work on a Postgres-based back end, with an API based around recent work on the flat sequence model. It doesn't do very much yet.

### Setting Up

You need Postgres 9.5 or better. I **highly** recommend using [Postgres.app](http://postgresapp.com/) which is much much easier than dealing with a "real" installation. You need to add its binaries to your path, something along the lines of

```
export PATH=$PATH:/Applications/Postgres.app/Contents/Versions/latest/bin
```

Next you can run the following to create the database and user.

```
psql -c 'create user postgres createdb'
psql -c 'create database gem' -U postgres
```

Now initialize the database by running the migration script.

```
sbt sql/flywayMigrate
```

You can also just do `sql/flywayMigrate` from the sbt prompt if you already have sbt running. You will repeat this step each time the schema changes. It's not part of the build yet but probably will be soon.

If you ever want to wipe out the database and start over, you can do

```
psql -c 'drop database gem' -U postgres
```

And then re-run steps 2 and 3 above. At any time you can say

```
psql -U postgres -d gem
```

to poke around with the database on the commandline. For real work I recommend a more full-featured front end. I use [Toad](https://www.toadworld.com/products/toad-mac-edition) but there are a lot of options.

### Importing Old Programs

To import old programs into Postgres you need to create a symlink to some old programs first

```
ln -s /path/to/some/old/xml/files archive
```

You can then import from the `sbt` prompt:

```
sbt> importer/runMain gem.Importer 123     Import the first 123 programs.
sbt> importer/run                          Import everything; same as passing Int.MaxValue
```

Right now just a sketch is imported:

- referenced semesters;
- programs (structured id and title)
- observations (title, instrument)
- sequence steps, generically, with slices for
  - bias
  - dark
  - gcal (lamp, shutter)
  - science (offset p/q)
    - F2 (fpu, pre-imaging, exposure time, filter, lyot wheel, disperser)

There are no other instrument-specific slices for science steps yet.

### Enumerated Types

Enumerated types are represented by tables named `e_whatever` which are the source for generated code on the Scala side. After compiling if you look in `modules/core/target/scala-2.11/src_managed/gem` you will see the source. The rationale for this is that the database becomes the source of truth, which makes things like filter wavelengths, etc., available for querying and reporting.

It's not yet clear which bits could be data-driven and which need to exist in Scala code. We do need to write code against specific filters and so on but we could still read these from the database on the fly. TBD.

### Schema Updates

If you need to update the schema you can just make changes locally and then truncate user data and do a dump.

```
psql -c "truncate log; truncate program cascade; delete from gem_user where id != 'root'" -d gem -U postgres
pg_dump -U postgres -d gem > create.sql
```
