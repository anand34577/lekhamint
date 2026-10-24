// Package seed provides the default category set for new ledgers.
package seed

import (
	_ "embed"
	"encoding/json"
	"fmt"
	"time"

	"github.com/kosh-app/kosh/server/internal/ids"
	"github.com/kosh-app/kosh/server/internal/syncer"
)

//go:embed default-categories.json
var defaultCategoriesJSON []byte

type node struct {
	Name     string `json:"name"`
	Icon     string `json:"icon"`
	Color    string `json:"color"`
	Nature   string `json:"nature"`
	Children []node `json:"children"`
}

type file struct {
	Expense []node `json:"expense"`
	Income  []node `json:"income"`
}

// DefaultCategories returns write ops that create the default category tree.
// Children inherit their parent's colour and nature unless they set their own.
func DefaultCategories() ([]syncer.WriteOp, error) {
	var f file
	if err := json.Unmarshal(defaultCategoriesJSON, &f); err != nil {
		return nil, fmt.Errorf("parse default categories: %w", err)
	}
	now := time.Now().UnixMilli()
	var ops []syncer.WriteOp
	add := func(kind string, n node, parentID string, order int, inheritColor, inheritNature string) string {
		id := ids.NewV7()
		color, nature := n.Color, n.Nature
		if color == "" {
			color = inheritColor
		}
		if nature == "" {
			nature = inheritNature
		}
		data := map[string]any{"name": n.Name, "kind": kind, "icon": n.Icon, "sort_order": int64(order), "created_at": now}
		if color != "" {
			data["color"] = color
		}
		if nature != "" && kind == "expense" {
			data["nature"] = nature
		}
		if parentID != "" {
			data["parent_id"] = parentID
		}
		ops = append(ops, syncer.WriteOp{Entity: "categories", ID: id, Data: data, MustNotExist: true})
		return id
	}
	groups := []struct {
		kind  string
		roots []node
	}{{"expense", f.Expense}, {"income", f.Income}}
	for _, g := range groups {
		kind := g.kind
		for i, root := range g.roots {
			pid := add(kind, root, "", i, "", "")
			for j, child := range root.Children {
				add(kind, child, pid, j, root.Color, root.Nature)
			}
		}
	}
	return ops, nil
}
