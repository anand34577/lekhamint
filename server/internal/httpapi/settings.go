package httpapi

import (
	"bytes"
	"encoding/json"
	"errors"
	"net/http"

	"github.com/anand34577/lekhamint/server/internal/store"
)

// Settings are a person's own app choices (theme, chosen book, assistant options,
// dismissed tips). They live on the server so they are the same in every browser and on
// every device, and are never shared with other members of a book.

func (s *Server) getSettings(w http.ResponseWriter, r *http.Request) {
	m, err := store.GetSettings(r.Context(), s.DB, currentUser(r).ID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, map[string]any{"settings": m})
}

// putSetting stores {"value": <any JSON>} under the key; a null value removes it.
func (s *Server) putSetting(w http.ResponseWriter, r *http.Request) {
	key := r.PathValue("key")
	if !store.ValidSettingKey(key) {
		s.fail(w, r, badReq("a setting name is up to 64 lower-case letters, digits, dots, dashes or underscores"))
		return
	}
	var in struct {
		Value json.RawMessage `json:"value"`
	}
	if err := decode(w, r, store.MaxSettingBytes+1024, &in); err != nil {
		s.fail(w, r, err)
		return
	}
	v := bytes.TrimSpace(in.Value)
	uid := currentUser(r).ID
	if len(v) == 0 || bytes.Equal(v, []byte("null")) {
		if err := store.DeleteSetting(r.Context(), s.DB, uid, key); err != nil {
			s.fail(w, r, err)
			return
		}
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if len(v) > store.MaxSettingBytes {
		s.fail(w, r, badReq("that setting is too large"))
		return
	}
	err := store.PutSetting(r.Context(), s.DB, uid, key, json.RawMessage(v))
	if errors.Is(err, store.ErrTooManySettings) {
		writeError(w, http.StatusConflict, "too_many_settings", "too many saved settings; remove some first", nil)
		return
	}
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"key": key, "value": json.RawMessage(v)})
}

func (s *Server) deleteSetting(w http.ResponseWriter, r *http.Request) {
	key := r.PathValue("key")
	if !store.ValidSettingKey(key) {
		s.fail(w, r, badReq("unknown setting"))
		return
	}
	if err := store.DeleteSetting(r.Context(), s.DB, currentUser(r).ID, key); err != nil {
		s.fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
