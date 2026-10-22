package main

import (
	"bytes"
	"errors"
	"reflect"
	"strings"
	"testing"
)

const guiExemplo = "windowType = {\n\tname = \"province_window\"\n\tposition = { x=0 y=0 }\n}\n"

func TestInserirDepoisDaLinhaDaJanela(t *testing.T) {
	bloco := montarBloco(moldeGui, 40, 430)
	novo, err := inserir([]byte(guiExemplo), bloco)
	if err != nil {
		t.Fatal(err)
	}
	esperado := "windowType = {\n\tname = \"province_window\"\n" + string(bloco) + "\tposition = { x=0 y=0 }\n}\n"
	if string(novo) != esperado {
		t.Fatalf("resultado inesperado:\n%s", novo)
	}
	if !bytes.Contains(novo, []byte("position = { x = 40 y = 430 }")) {
		t.Fatal("posição não foi preenchida no molde")
	}
}

func TestInserirMantemCRLF(t *testing.T) {
	crlf := strings.ReplaceAll(guiExemplo, "\n", "\r\n")
	novo, err := inserir([]byte(crlf), montarBloco(moldeGui, 30, 440))
	if err != nil {
		t.Fatal(err)
	}
	if soltas := bytes.Count(novo, []byte("\n")) - bytes.Count(novo, []byte("\r\n")); soltas != 0 {
		t.Fatalf("%d quebras de linha LF soltas num arquivo CRLF", soltas)
	}
	if !bytes.HasPrefix(novo, []byte(crlf[:strings.Index(crlf, "\tposition")])) {
		t.Fatal("o começo do arquivo original foi alterado")
	}
}

func TestInserirDuasVezesFalha(t *testing.T) {
	uma, _ := inserir([]byte(guiExemplo), montarBloco(moldeGui, 30, 440))
	if _, err := inserir(uma, montarBloco(moldeGui, 30, 440)); !errors.Is(err, errJaInstalado) {
		t.Fatalf("esperava errJaInstalado, veio %v", err)
	}
}

func TestInserirSemJanelaFalha(t *testing.T) {
	if _, err := inserir([]byte("windowType = { name = \"outra\" }\n"), []byte("x\n")); !errors.Is(err, errSemJanela) {
		t.Fatalf("esperava errSemJanela, veio %v", err)
	}
}

func TestBibliotecasSteam(t *testing.T) {
	vdf := "\"libraryfolders\"\n{\n\t\"0\"\n\t{\n\t\t\"path\"\t\t\"C:\\\\Program Files (x86)\\\\Steam\"\n\t}\n" +
		"\t\"1\"\n\t{\n\t\t\"path\"\t\t\"D:\\\\SteamLibrary\"\n\t\t\"label\"\t\t\"\"\n\t}\n}\n"
	got := bibliotecasSteam(vdf)
	want := []string{`C:\Program Files (x86)\Steam`, `D:\SteamLibrary`}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %q, want %q", got, want)
	}
}
