(() => {
  const category = document.getElementById("atlas-type");
  const search = document.getElementById("atlas-search");
  if (!category || !category.form) return;
  category.addEventListener("change", () => {
    if (search) search.value = "";
    category.form.requestSubmit();
  });
})();
