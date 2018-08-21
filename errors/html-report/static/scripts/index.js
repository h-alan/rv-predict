$(document).ready(function() {
  var table = $("#myTable").DataTable({
    fixedHeader: {
      headerOffset: $("#table-header").outerHeight(true),
      header: true
    },
    paging: false,
    ordering: true,
    dom: 'rtB',
    stateSave: true,
    stateDuration: -1,
    buttons: [{
        extend: 'csv',
        filename: 'errors'
      },
      {
        extend: 'excel',
        filename: 'errors'
      },
      {
        extend: 'pdf',
        filename: 'errors'
      },
    ],
    columns: [{
        "name": "id"
      },
      {
        "name": "error"
      },
      {
        "name": "function",
        "className": "function"
      },
      {
        "name": "file"
      },
      {
        "name": "frequency"
      }
    ],
    initComplete: function(settings, json) {
      var table = this.api()

      // Hack: Hide the column when performing columns reorder.  
      function displayColumnThatWasHidden() {
        var $header = $(table.header()[0])
        var $columns = $('th', $header)
        window.$columns = $columns
        for (var i = 0; i < $columns.length; i++) {
          if ($columns[i].style.display === 'none') {
            $columns[i].style.display = "table-cell"
            var columnIndex = parseInt($columns[i].getAttribute('data-column-index'))
            var trs = document.getElementsByTagName('tr')
            for (var j = 0; j < trs.length; j++) {
              var td = trs[j].children[columnIndex]
              if (td && td.style.display === 'none')
                td.style.display = "table-cell"
            }
            break
          }
        }
      }

      var colReorder = new $.fn.dataTable.ColReorder(table, {
        realtime: false
      })
      var oldMouseMove = colReorder._fnMouseMove.bind(colReorder)
      colReorder._fnMouseMove = function(e) {
        oldMouseMove(e) // Call old _fnMouseMove function 

        var draggingTarget = colReorder.s.mouse.target
        var columnIndex = parseInt(draggingTarget.getAttribute('data-column-index'))

        if (draggingTarget && colReorder.dom.drag) {
          if (draggingTarget.style.display !== 'none') { // Hide column `columnIndex`
            var trs = document.getElementsByTagName('tr')
            for (var i = 0; i < trs.length; i++) {
              var td = trs[i].children[columnIndex]
              if (td && td.style.display !== 'none') {
                td.style.display = "none"
              }
            }

            // Reinitialize aoTargets, which is used to locate pointer
            colReorder.s.aoTargets = []
            colReorder._fnRegions()
          }

          draggingTarget.style.display = "none"

          if (colReorder.dom.drag) {
            $("th", colReorder.dom.drag).show()
          }
        }
      }

      var oldMouseUp = colReorder._fnMouseUp.bind(colReorder)
      colReorder._fnMouseUp = function(e) {
        if (colReorder.dom.drag) {
          displayColumnThatWasHidden()
        }
        oldMouseUp(e)
      }

      // Hack: Disable default table header click event.  
      function rebindTableHeaderClickEvents() {
        var $header = $(table.header()[0])
        var $ths = $('th', $header)
        for (var i = 0; i < $ths.length; i++) {
          var $th = $($ths[i])
          $th.off('click')
          $th.on('click', function(event) {
            var direction = event.target.getAttribute('data-direction') || 'asc'
            if (direction === 'asc') {
              direction = 'desc'
            } else {
              direction = 'asc'
            }
            event.target.setAttribute('data-direction', direction)
            sortColumns()
          })
        }
      }
      /**
       * Sort columns from left to right by attribute `data-direction`.  
       */
      function sortColumns() {
        var $header = $(table.header()[0])
        var $ths = $('th', $header)
        var orders = []
        for (var i = 0; i < $ths.length; i++) {
          var direction = $ths[i].getAttribute('data-direction')
          orders.push([i, direction])
        }
        table.order(orders).draw()
        try {
          if (typeof(Storage) !== "undefined") {
            sessionStorage.myTable_columnOrders = JSON.stringify(orders)
          }
        } catch (error) {}
      }
      /**
       * Load and set `data-direction` attributes to table headers
       */
      function initColumnsSorting() {
        var $header = $(table.header()[0])
        var $ths = $('th', $header)
        var orders = []
        try {
          if (typeof(Storage) !== "undefined") {
            orders = JSON.parse(sessionStorage.myTable_columnOrders)
          }
        } catch (error) {}

        for (var i = 0; i < $ths.length; i++) {
          if (i < orders.length) {
            $ths[i].setAttribute('data-direction', orders[i][1]) // [offset, direction]
          } else {
            $ths[i].setAttribute('data-direction', 'asc')
          }
        }
        sortColumns()
      }
      /**
       * Format `File` column to fix sorting the line numbers in lexical order
       */
      function formatFileColumn() {
        var $header = $(table.header()[0])
        var $ths = $('th', $header)
        var offset = 0;
        for (; offset < $ths.length; offset++) {
          if ($ths[offset].innerText.trim().toLowerCase().match(/^File$/i)) {
            break;
          }
        }
        var data = {}; // key is filename, value is max lineNumber
        var $trs = $('tr', table.body()[0])
        for (var i = 0; i < $trs.length; i++) {
          var tr = $trs[i]
          var td = tr.children[offset]
          if (!td) {
            continue
          }
          var text = td.innerText
          var index = text.lastIndexOf(":")
          var filename = text.slice(0, index)
          var line = parseInt(text.slice(index + 1)) || 0
          if (!(filename in data) || line > data[filename]) {
            data[filename] = line
          }
        }
        for (var i = 0; i < $trs.length; i++) {
          var tr = $trs[i]
          var td = tr.children[offset]
          if (!td) {
            continue
          }
          var text = td.innerText
          var index = text.lastIndexOf(":")
          var filename = text.slice(0, index)
          var line = parseInt(text.slice(index + 1)) || 0
          var difference = data[filename].toString().length - line.toString().length
          var newText = filename + ":"
          for (var j = 0; j < difference; j++) {
            newText += String.fromCharCode(0)
          }
          newText += line
          var a = td.querySelector('a')
          if (a) {
            a.innerText = newText
            table.row(tr).data()[offset] = a.outerHTML
          }
        }
      }

      function checkEmptyTable() {
        var $emptyTd = $(".dataTables_empty")
        if ($emptyTd.length) {
          $emptyTd.html("<span>Let's celebrate! There are no errors in your report.</span>")
        }
      }

      table.on('column-reorder', function(e, settings, details) {
        rebindTableHeaderClickEvents()
        sortColumns()
      })

      formatFileColumn()
      initColumnsSorting()
      rebindTableHeaderClickEvents()
      checkEmptyTable()
    }
  });

  $(window).bind('beforeunload', function() {
    try {
      if (typeof(Storage) !== "undefined") {
        sessionStorage.myTable_searchValue = $("#table-search").val();
        sessionStorage.myTable_scrollTop = $(window).scrollTop();
      }
    } catch (error) {}
  })

  try {
    if (typeof(Storage) !== "undefined") {
      if (sessionStorage.myTable_searchValue) {
        $("#table-search").val(sessionStorage.myTable_searchValue || '');
      }
      if (sessionStorage.myTable_scrollTop) {
        $(window).scrollTop(sessionStorage.myTable_scrollTop);
      }
    }
  } catch (error) {}

  $("#table-search")
    .on('input', function(event) {
      var text = this.value.trim();
      var regex;
      var words = text.split(/\s/);
      var terms = {};
      var cols = table.settings().init().columns;
      for (var i = 0; i < cols.length; i++) {
        terms[cols[i].name] = "";
      }
      var global = "";
      for (var i = 0; i < words.length; i++) {
        var word = words[i];
        var idx = word.indexOf(':');
        if (idx !== -1) {
          var label = word.substring(0, idx);
          var term = word.substring(idx + 1);
          if (label in terms) {
            terms[label] = terms[label] + " " + term;
          } else {
            global = global + " " + word;
          }
        } else {
          global = global + " " + word;
        }
      }
      for (var col in terms) {
        table.column(col + ":name").search(terms[col]);
      }
      table.search(global).draw();
    });
});