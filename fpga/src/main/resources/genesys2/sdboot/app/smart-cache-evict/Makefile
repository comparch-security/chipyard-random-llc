
MAKE = make
CXX = g++
CXXFLAGS = --std=c++11 -O3 -I.

TARGETS = \
	run/test-evict-ratio \
	run/test-evict \
	run/test-evict-combine \

OBJECTS = \
	cache/list.o \
	cache/cache.o \
	cache/algorithm.o \
	common/definitions.o \
	util/random.o \
	util/statistics.o \

HEADERS = $(wildcard cache/*.hpp) $(wildcard database/*.hpp) $(wildcard util/*.hpp)

all: $(TARGETS)

$(OBJECTS): %.o:%.cpp $(HEADERS)
	$(CXX) $(CXXFLAGS) -c $< -o $@

$(TARGETS): run/% : test/%.cpp $(OBJECTS)
	$(CXX) $(CXXFLAGS) $^ -lpthread -o $@

clean:
	-rm $(TARGETS) $(OBJECTS)
