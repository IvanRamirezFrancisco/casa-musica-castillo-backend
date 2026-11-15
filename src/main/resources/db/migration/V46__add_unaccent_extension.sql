-- V46__add_unaccent_extension.sql
-- Habilita la extensión unaccent para búsquedas insensibles a tildes y diacríticos.
-- Se requiere para normalizar el texto en búsquedas LIKE (Fase B1).
-- Se crea de forma segura sin afectar datos existentes.

CREATE EXTENSION IF NOT EXISTS unaccent;
