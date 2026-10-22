package main

import (
	"bytes"
	"errors"
	"regexp"
	"strconv"
	"strings"
)

// Mesma marca usada pelo instalar_painel_provincia.py, para os dois
// instaladores reconhecerem o trabalho um do outro.
const marcaInicio = "### >>> Populacao Hibrida: inicio"

var (
	janelaAlvo        = regexp.MustCompile(`name\s*=\s*"province_window"`)
	caminhoBiblioteca = regexp.MustCompile(`"path"\s+"([^"]+)"`)

	errJaInstalado = errors.New("o provinceview.gui do jogo já contém o painel; ele deveria ser o arquivo original")
	errSemJanela   = errors.New(`não encontrei name = "province_window" no provinceview.gui`)
)

// montarBloco preenche a posição do texto no molde do elemento de interface.
func montarBloco(molde []byte, x, y int) []byte {
	b := bytes.ReplaceAll(molde, []byte("%X%"), []byte(strconv.Itoa(x)))
	return bytes.ReplaceAll(b, []byte("%Y%"), []byte(strconv.Itoa(y)))
}

// inserir coloca o bloco logo depois da linha com o nome da janela da
// província; a ordem dos filhos dentro de um windowType não importa para o
// EU4. Tudo é feito em bytes para não mudar a codificação do arquivo
// original, e o bloco segue as quebras de linha dele (CRLF no Windows).
func inserir(conteudo, bloco []byte) ([]byte, error) {
	if bytes.Contains(conteudo, []byte(marcaInicio)) {
		return nil, errJaInstalado
	}
	achado := janelaAlvo.FindIndex(conteudo)
	if achado == nil {
		return nil, errSemJanela
	}
	if bytes.Contains(conteudo, []byte("\r\n")) {
		bloco = bytes.ReplaceAll(bytes.ReplaceAll(bloco, []byte("\r\n"), []byte("\n")), []byte("\n"), []byte("\r\n"))
	}
	fimDaLinha := bytes.IndexByte(conteudo[achado[1]:], '\n')
	if fimDaLinha < 0 {
		return nil, errSemJanela
	}
	corte := achado[1] + fimDaLinha + 1
	novo := make([]byte, 0, len(conteudo)+len(bloco))
	novo = append(novo, conteudo[:corte]...)
	novo = append(novo, bloco...)
	return append(novo, conteudo[corte:]...), nil
}

// bibliotecasSteam lê as pastas de biblioteca do arquivo libraryfolders.vdf
// do Steam (quem instala jogos em outro disco tem mais de uma).
func bibliotecasSteam(vdf string) []string {
	var pastas []string
	for _, m := range caminhoBiblioteca.FindAllStringSubmatch(vdf, -1) {
		pastas = append(pastas, strings.ReplaceAll(m[1], `\\`, `\`))
	}
	return pastas
}
